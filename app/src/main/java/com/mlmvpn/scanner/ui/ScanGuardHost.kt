package com.mlmvpn.scanner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.ScanGuard
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.DialogSurface
import com.mlmvpn.scanner.ui.theme.Primary
import com.mlmvpn.scanner.ui.theme.TextMuted
import com.mlmvpn.scanner.ui.theme.TextPrimary
import com.mlmvpn.scanner.ui.theme.iosButtonBorder
import com.mlmvpn.scanner.ui.theme.iosButtonColors

/**
 * The one dialog that says "your scan is using the connection right now".
 *
 * Mounted once, at the app's root, rather than on each screen that can collide with a scan. The
 * collision is not a property of any one screen -- the connection list, Quick Connect, the game
 * booster, the five transports and the tile can all raise it -- and a copy per screen would be
 * six chances to word the same question differently and one guaranteed omission.
 *
 * The two answers are the only two that exist. There is no third option where both things happen:
 * a tunnel takes the default route out from under every socket the scan has open, and a delay test
 * measured while a scan is saturating the link is a number about the scan, not about the server.
 */
@Composable
fun ScanGuardHost() {
    val pending by ScanGuard.pending.collectAsState()
    val request = pending ?: return
    val scout = ScanGuard.isScoutBusy() && !com.mlmvpn.scanner.data.ScannerManager.isScanning.value

    AlertDialog(
        onDismissRequest = { ScanGuard.dismiss() },
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .border(0.7.dp, Color.White.copy(alpha = 0.15f), CardShape),
        containerColor = DialogSurface,
        shape = CardShape,
        title = {
            Text(
                if (scout) com.mlmvpn.scanner.store.tr("MLM Scout در حال بررسی است", "MLM Scout is running")
                else stringResource(R.string.scan_busy_title),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                when {
                    scout -> com.mlmvpn.scanner.store.tr(
                        "Scout الان روش‌ها را روی خودِ اینترنت شما می‌سنجد؛ کار دیگری در این لحظه عددها را خراب می‌کند. صبر می‌کنید یا Scout را متوقف کنم؟ نتایج تا همین لحظه می‌ماند.",
                        "Scout is measuring the methods on your own line right now; doing this now would spoil its numbers. Wait, or stop Scout? The results so far are kept.",
                    )
                    request.reason == ScanGuard.Reason.CONNECT_VPN -> stringResource(R.string.scan_busy_connect_body)
                    else -> stringResource(R.string.scan_busy_delay_body)
                },
                color = TextMuted,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            // Stopping the scan is the destructive half, so it is the one that has to be chosen
            // deliberately -- but it is also the one the user pressed a button expecting, which
            // is why it is here rather than hidden behind the dismiss slot.
            Button(
                onClick = { ScanGuard.stopScanAndProceed() },
                colors = iosButtonColors(Primary),
                border = iosButtonBorder(Primary),
            ) {
                Text(
                    if (scout) com.mlmvpn.scanner.store.tr("توقف Scout و ادامه", "Stop Scout and go on")
                    else stringResource(R.string.scan_busy_stop_scan),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { ScanGuard.dismiss() }) {
                Text(stringResource(R.string.scan_busy_wait), color = TextMuted)
            }
        },
    )
}
