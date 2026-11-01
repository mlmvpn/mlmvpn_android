package com.mlmvpn.scanner.ui.arena

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.engines.arena.ArenaEngine
import com.mlmvpn.scanner.engines.arena.ArenaPanels
import com.mlmvpn.scanner.engines.arena.ArenaStore
import com.mlmvpn.scanner.store.tr
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.settings.SettingsRow

/** The Cloud tab's door into «میدان کانفیگ»: one row, as Settings would put it. */
@Composable
fun ArenaEntryCard(onOpen: () -> Unit, modifier: Modifier = Modifier.padding(vertical = 8.dp)) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ArenaStore.load(context) } }
    val history by ArenaStore.history.collectAsState()
    val live by ArenaEngine.state.collectAsState()
    val winner = history.firstOrNull()?.board?.winner
    SettingsGroup(modifier = modifier) {
        SettingsRow(
            title = tr("میدان کانفیگ", "Config Arena"),
            icon = Icons.Default.EmojiEvents, tint = Ios.Yellow,
            subtitle = when {
                live.running -> tr("مسابقه در جریان است", "A race is running")
                winner != null -> tr("آخرین برنده: ", "Last winner: ") + (ArenaPanels.byId(winner)?.name ?: "")
                else -> tr("پنل‌هایتان را با یک دکمه مسابقه بدهید", "Race your panels with one tap")
            },
            onClick = onOpen,
        )
    }
}
