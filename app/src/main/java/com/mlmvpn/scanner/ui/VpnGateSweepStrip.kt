package com.mlmvpn.scanner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.engines.vpngate.VpnGateSweep
import com.mlmvpn.scanner.ui.theme.BorderDark
import com.mlmvpn.scanner.ui.theme.Primary
import com.mlmvpn.scanner.ui.theme.SurfaceDark
import com.mlmvpn.scanner.ui.theme.TextDim

/**
 * Progress strip for the VPN Gate bulk tests, modelled on the delay-test strip in NodesTab:
 * label + count + percentage, a filling bar, and a ✕ that stops the sweep.
 *
 * Place it OUTSIDE the scrolling container — these sweeps take minutes over hundreds of
 * servers and the whole point is that the user can scroll the results while still seeing how
 * far along it is. Draws nothing when no sweep is running.
 */
@Composable
fun VpnGateSweepStrip(modifier: Modifier = Modifier) {
    val sweep by VpnGateSweep.stateFlow.collectAsState()

    AnimatedVisibility(visible = sweep != null) {
        // Held so the row keeps rendering its last values through the exit animation instead
        // of snapping to a blank bar the instant the sweep clears.
        val s = sweep ?: return@AnimatedVisibility
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${s.kind.labelFa}: ${s.done} / ${s.total}  ·  ${s.percent}٪",
                color = Primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp),
            )
            LinearProgressIndicator(
                progress = s.fraction,
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Primary,
                trackColor = BorderDark,
            )
            Icon(
                imageVector = Icons.Default.Cancel,
                contentDescription = "توقف تست",
                tint = TextDim,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(16.dp)
                    .clickable { VpnGateSweep.cancel() },
            )
        }
    }
}
