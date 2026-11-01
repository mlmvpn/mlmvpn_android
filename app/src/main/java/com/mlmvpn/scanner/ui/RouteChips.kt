package com.mlmvpn.scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What the app's own settings do to the connection, at a glance: split tunnelling, Local Network
 * sharing and the Smart Iran route. They apply to every method, so every method's page shows them
 * (first on Outline's). Re-read every few seconds while the page is on screen, so a change made in
 * Settings shows when the user comes back.
 */
@Composable
fun RouteChips(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var chips by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    LaunchedWhileVisible(Unit) {
        while (true) {
            chips = withContext(Dispatchers.IO) { read(context) }
            delay(3_000)
        }
    }
    if (chips.isEmpty()) return
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips.size) { i ->
            val (text, on) = chips[i]
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Ios.CardOverWallpaper).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Ios.Green else Ios.SecondaryLabel))
                Spacer(Modifier.width(6.dp))
                Text(text, color = Ios.Label, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

private fun read(context: android.content.Context): List<Pair<String, Boolean>> {
    val split = com.mlmvpn.core.tunnel.SplitTunnelSettings(context)
    val n = split.packages().size
    val splitChip = when (split.mode()) {
        com.mlmvpn.core.tunnel.SplitTunnelSettings.Mode.ALL -> tr("همهٔ برنامه‌ها از تونل", "All apps tunnelled") to false
        com.mlmvpn.core.tunnel.SplitTunnelSettings.Mode.INCLUDE -> tr("تقسیم تونل: فقط ${com.mlmvpn.scanner.ui.amnezia.fa(n)} برنامه", "Split: only $n apps") to true
        com.mlmvpn.core.tunnel.SplitTunnelSettings.Mode.EXCLUDE -> tr("تقسیم تونل: ${com.mlmvpn.scanner.ui.amnezia.fa(n)} برنامه بیرون", "Split: $n apps bypass") to true
    }
    val lan = com.mlmvpn.scanner.lan.LanShare.sharingActive(context)
    val iran = com.mlmvpn.core.tunnel.SmartIranRoute.enabled(context)
    return listOf(
        splitChip,
        (if (lan) tr("اشتراک در شبکهٔ محلی: روشن", "LAN sharing: on") else tr("اشتراک در شبکهٔ محلی: خاموش", "LAN sharing: off")) to lan,
        (if (iran) tr("مسیریابی هوشمند ایران: روشن", "Smart Iran routing: on") else tr("مسیریابی هوشمند ایران: خاموش", "Smart Iran routing: off")) to iran,
    )
}
