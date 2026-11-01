package com.mlmvpn.scanner.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The four pinned apps, in a glass bubble at the bottom of the home screen.
 *
 * Genuinely frosted, on every Android version. `Modifier.blur` was not an option -- it is a
 * no-op below API 31, so on anything older than Android 12 the dock would have lost its backdrop
 * entirely and the icons would float on nothing. See [frostedGlass] for how the blur is built
 * instead: a small copy of the wallpaper, sliced to the dock's own rectangle and stretched back.
 *
 * Like an iPhone's dock this is drawn on the home screen only. It is not a bottom nav bar: open
 * a feature and it goes away with the rest of the home screen, and back brings both back.
 */
@Composable
fun GlassDock(
    apps: List<HomeApp>,
    tileSize: Dp,
    onOpen: (HomeApp) -> Unit,
    modifier: Modifier = Modifier,
    /** Off while the home screen's search covers the dock, so a remote's focus stays in the search. */
    focusable: Boolean = true,
) {
    // Read once for the whole dock rather than per icon: every AppIconCell would otherwise
    // subscribe to the same four flows separately.
    val live = ActiveEngines.ids()

    Row(
        modifier = modifier
            // Capped FIRST and filled second, and the order is the whole point. Bounding the
            // width alone made the Row wrap its content, which left SpaceEvenly with no free
            // space to distribute and pushed the four icons into each other. Capping the incoming
            // constraint and then filling it gives a bar that is the screen's width on a phone
            // and 560dp on anything larger, with the icons evenly spread inside it either way.
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            // Real frosted glass: the dock refracts the wallpaper slice actually behind it.
            // `overWallpaper = true` unconditionally, because the home screen always sits on the
            // wallpaper regardless of what the "wallpaper on every screen" setting says.
            // `underScrim = false` because the home screen does not dim its wallpaper; adding
            // the scrim here would make the dock darker than the board it sits on.
            .frostedGlass(
                RoundedCornerShape(30.dp),
                overWallpaper = true,
                underScrim = false,
            )
            .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        apps.forEach { app ->
            // Captions are off by default -- the same choice iOS makes, since four labelled
            // icons turn a bubble into a toolbar -- but the shapes are only obvious once you
            // already know them, so Display can turn them back on.
            AppIconCell(
                app = app,
                tileSize = tileSize,
                showLabel = Appearance.dockLabels,
                connected = app.id in live,
                // The click is a focus target of its own, so it goes too while covered.
                onClick = if (focusable) ({ onOpen(app) }) else null,
                modifier = Modifier.tvFocusable(enabled = focusable, cornerRadius = 18, onPress = { onOpen(app) }),
            )
        }
    }
}
