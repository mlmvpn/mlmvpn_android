package com.mlmvpn.scanner.widget

import android.content.Context
import android.content.Intent
import com.mlmvpn.scanner.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Opens the app on one tile's own page -- a method a widget stands for, or «ویجت استدیو» -- from
 * outside the app.
 *
 * The request travels as an extra on MainActivity's intent and is handed to the screen through
 * [pending], whether the activity is being created or was already running (onNewIntent): AppScreen
 * reads it and navigates, then clears it. An app already open keeps everything it was doing -- a
 * scan, a half-filled form -- instead of being torn down and rebuilt to show one page.
 */
object WidgetOpen {

    const val EXTRA_OPEN_TILE = "open_tile"

    /** The tile id the app should show next; AppScreen consumes it. */
    val pending = MutableStateFlow<String?>(null)

    fun intent(context: Context, tile: String): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_TILE, tile)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun open(context: Context, tile: String) {
        runCatching { context.startActivity(intent(context, tile)) }
    }

    /** A row of Settings a chat's card names (GerehAppLinks key; q12): Settings opens on its page. */
    const val EXTRA_OPEN_SETTING = "open_setting"

    /** The settings row the app should show next; AppScreen consumes it. */
    val pendingSetting = MutableStateFlow<String?>(null)

    fun openSetting(context: Context, key: String) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_SETTING, key)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    /** Called by MainActivity with every intent it receives; takes the request off it, once. */
    fun offer(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OPEN_SETTING)?.takeIf { it.isNotBlank() }?.let {
            intent.removeExtra(EXTRA_OPEN_SETTING)
            pendingSetting.value = it
        }
        val tile = intent?.getStringExtra(EXTRA_OPEN_TILE)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_OPEN_TILE)
        pending.value = tile
    }
}
