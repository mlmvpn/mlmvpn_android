package com.mlmvpn.scanner.ui

import com.mlmvpn.scanner.ui.theme.*
import com.mlmvpn.scanner.ui.home.frostedGlass
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
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.S

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
    com.mlmvpn.scanner.utils.SafeStart.service(context, stop)
    MyVpnService.isRunning = false
    MyVpnService.connectedNodeId = null
}

/**
 * Elegant confirmation modal shown when the user tries to start an Xray action while
 * the WireGuard trial is active.
 */
@Composable
fun WireguardConflictDialog(
    message: String = S(R.string.the_wireguard_connection_game_trial_is_active),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .frostedGlass(CardShape)
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
            Text(S(R.string.wireguard_is_active), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(22.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.Transparent, ControlShape)
                        .border(1.dp, BorderDark, ControlShape)
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text(S(R.string.cancel_3), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Primary, ControlShape)
                        .clickable { onConfirm() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text(S(R.string.yes_turn_it_off), color = BgDark, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
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
    com.mlmvpn.scanner.engines.rstaspoof.RstaSpoofManager.isRunningFlow.value -> S(R.string.sni_anti_filter_engine)
    MyVpnService.isRunning && MyVpnService.connectedNodeId == "game_uae_trial" -> S(R.string.wireguard_game_trial)
    MyVpnService.isRunning -> S(R.string.the_current_vpn_engine)
    else -> null
}

/** Restarts the whole app process — the only reliable way to unload a gomobile Go runtime. */
fun restartAppProcess(context: Context) {
    val ctx = context.applicationContext
    // Mark it, or this self-kill is indistinguishable from the native exit(255) we are chasing --
    // both land in ApplicationExitInfo as REASON_EXIT_SELF.
    com.mlmvpn.scanner.CrashReporter.noteDeliberateExit(ctx, "restartAppProcess")
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
        com.mlmvpn.scanner.utils.SafeStart.service(context, stop)
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
 * Disconnects the VPN.
 *
 * Nothing engine-specific happens here any more. This briefly carried a "relaunch the app after
 * disconnecting" workaround for the native core that exits(255) after its own teardown; that core
 * now runs in its own process (see Tun2proxyHostService), so a disconnect is just a disconnect.
 */
fun stopVpnSafely(context: Context) {
    com.mlmvpn.scanner.CrashReporter.note("stopVpnSafely")
    val stop = Intent(context, MyVpnService::class.java).apply { action = "STOP" }
    com.mlmvpn.scanner.utils.SafeStart.service(context, stop)
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
                .frostedGlass(CardShape)
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
            Text(S(R.string.enginename_is_on, engineName), color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                S(R.string.to_start_the_test_enginename_has_to, engineName),
                color = TextMuted, fontSize = 13.sp, lineHeight = 20.sp
            )
            Spacer(Modifier.height(22.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.Transparent, ControlShape)
                        .border(1.dp, BorderDark, ControlShape)
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text(S(R.string.cancel_3), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium) }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Primary, ControlShape)
                        .clickable { onConfirm() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text(S(R.string.confirm_and_refresh), color = BgDark, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
