package com.mlmvpn.scanner.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.chat.GerehWipeRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.mlmvpn.chat.bridge.GerehService
import org.mlmvpn.chat.bridge.GerehWipe

/**
 * The Gereh tile's own menu on a long press (Ehsan, 30 Oct 2026): Gereh in the background or only
 * while open, every Gereh notification on or off, and the home screen's edit mode, which a long
 * press on any other tile opens. The switches are flag files Gereh's own process reads.
 *
 * Below them the three deletions (q3, 31 Oct 2026), each asked once more before it is done:
 * clear Gereh's data, delete the account, and take Gereh out of the app ([GerehWipe]).
 */
object GerehTileMenu {
    val open = mutableStateOf(false)

    /** The deletion under way ([GerehWipe] mode), 0 for none. Outlives the dialog. */
    internal val working = mutableIntStateOf(0)

    /** The work outlives the dialog's composition: it may take tens of seconds. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}

@Composable
fun GerehTileMenuDialog(onEditHome: () -> Unit, onRemoved: () -> Unit = {}) {
    if (!GerehTileMenu.open.value) return
    val context = LocalContext.current
    var background by remember { mutableStateOf(!GerehService.flag(context, GerehService.BACKGROUND_OFF)) }
    var muted by remember { mutableStateOf(GerehService.flag(context, GerehService.MUTED)) }
    var asking by remember { mutableIntStateOf(0) }
    val working = GerehTileMenu.working.intValue
    val close = { if (GerehTileMenu.working.intValue == 0) GerehTileMenu.open.value = false }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.gereh_tile_menu_title)) },
        text = {
            Column {
                SwitchRow(stringResource(R.string.gereh_tile_background), background) {
                    background = it
                    GerehService.setBackground(context, it)
                }
                Text(
                    stringResource(R.string.gereh_tile_background_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SwitchRow(stringResource(R.string.gereh_tile_mute), muted) {
                    muted = it
                    GerehService.setMuted(context, it)
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                if (working != 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(if (working == GerehWipe.CLEAR) R.string.gereh_wipe_clearing else R.string.gereh_wipe_deleting))
                    }
                } else {
                    WipeRow(stringResource(R.string.gereh_wipe_clear)) { asking = GerehWipe.CLEAR }
                    WipeRow(stringResource(R.string.gereh_wipe_account)) { asking = GerehWipe.ACCOUNT }
                    WipeRow(stringResource(R.string.gereh_wipe_remove)) { asking = GerehWipe.REMOVE }
                }
            }
        },
        confirmButton = { TextButton(onClick = close, enabled = working == 0) { Text(stringResource(R.string.gereh_tile_done)) } },
        dismissButton = {
            TextButton(onClick = {
                close()
                onEditHome()
            }, enabled = working == 0) { Text(stringResource(R.string.gereh_tile_edit_home)) }
        },
    )
    if (asking != 0) {
        val mode = asking
        AlertDialog(
            onDismissRequest = { asking = 0 },
            title = {
                Text(stringResource(when (mode) {
                    GerehWipe.CLEAR -> R.string.gereh_wipe_clear
                    GerehWipe.ACCOUNT -> R.string.gereh_wipe_account
                    else -> R.string.gereh_wipe_remove
                }))
            },
            text = {
                Text(stringResource(when (mode) {
                    GerehWipe.CLEAR -> R.string.gereh_wipe_clear_info
                    GerehWipe.ACCOUNT -> R.string.gereh_wipe_account_info
                    else -> R.string.gereh_wipe_remove_info
                }))
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = 0
                    GerehTileMenu.working.intValue = mode
                    val app = context.applicationContext
                    GerehTileMenu.scope.launch {
                        val ok = GerehWipeRunner.run(app, mode)
                        GerehTileMenu.working.intValue = 0
                        GerehTileMenu.open.value = false
                        val said = when {
                            !ok -> R.string.gereh_wipe_failed
                            mode == GerehWipe.CLEAR -> R.string.gereh_wipe_clear_done
                            mode == GerehWipe.ACCOUNT -> R.string.gereh_wipe_account_done
                            else -> R.string.gereh_wipe_remove_done
                        }
                        Toast.makeText(app, said, Toast.LENGTH_LONG).show()
                        if (mode == GerehWipe.REMOVE) onRemoved()
                    }
                }) { Text(stringResource(R.string.gereh_wipe_yes), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = 0 }) { Text(stringResource(R.string.gereh_wipe_no)) } },
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun WipeRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}
