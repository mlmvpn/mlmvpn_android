package com.mlmvpn.scanner.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.delay

/** The Settings page a row is drawn on, by its route ([SettingsRoutes]); null outside SettingsScreen. */
internal val LocalSettingsRoute = staticCompositionLocalOf<String?> { null }

/** The scroll of the [IosScreen] a row sits in, so a row the search opened can bring itself into view. */
internal val LocalSettingsScroll = staticCompositionLocalOf<ScrollState?> { null }

/**
 * The row the home screen's search opened Settings for, until it has been shown.
 *
 * The search knows a row by its page and its title -- the very words the row draws, in the language
 * on screen -- and sends Settings to that page. The rows do the rest themselves: each row on the
 * page whose title is one of [Target.titles] reports in, and once the page has settled, the first of
 * them that is actually there scrolls itself to the middle of the screen and glows for a moment.
 * The titles after the first stand in for a row that only exists in some states: the US exit, which
 * is listed only while the Gemini switch is on, or the app list of a routing mode that has one.
 */
internal object SettingsSpotlight {

    class Target(val route: String, val titles: List<String>, val at: Long) {
        /** The ranks of [titles] that are composed on the page right now. */
        internal val present = mutableSetOf<Int>()
        /** Set by the row that scrolled, so a second row of the same rank only glows. */
        internal var scrolled = false

        fun rankOf(title: String): Int = titles.indexOfFirst { it.trim() == title.trim() }

        fun best(): Int = present.minOrNull() ?: -1
    }

    var target by mutableStateOf<Target?>(null)
        private set

    /** Light up the row titled [titles]`[0]` (or the first stand-in there is) on page [route]. */
    fun request(route: String, titles: List<String>) {
        target = Target(route, titles.filter { it.isNotBlank() }, android.os.SystemClock.uptimeMillis())
    }

    fun finish(t: Target) {
        if (target === t) target = null
    }

    /** Long enough for a page still being built on a slow phone; past it, a request is history. */
    const val FRESH_MS = 8_000L

    /** From the row appearing to its glow: the page finishes laying out and the screen change lands. */
    const val SETTLE_MS = 350L
}

private class CoordinatesRef {
    var value: LayoutCoordinates? = null
}

/**
 * Lets the search land on this row: when it is the row Settings was opened for (see
 * [SettingsSpotlight]) it scrolls into the middle of the screen and glows briefly, the way iOS
 * shows a setting it was searched for. On every other visit it adds nothing.
 */
internal fun Modifier.settingsSpotlight(title: String): Modifier = composed {
    val t = SettingsSpotlight.target
    val route = LocalSettingsRoute.current
    val rank = if (t == null || route != t.route) -1 else t.rankOf(title)
    if (t == null || rank < 0) return@composed this

    val scroll = LocalSettingsScroll.current
    val glow = remember(t) { Animatable(0f) }
    val coords = remember { CoordinatesRef() }

    DisposableEffect(t, rank) {
        t.present += rank
        onDispose { t.present -= rank }
    }
    LaunchedEffect(t, rank) {
        if (android.os.SystemClock.uptimeMillis() - t.at > SettingsSpotlight.FRESH_MS) return@LaunchedEffect
        delay(SettingsSpotlight.SETTLE_MS)
        // Only the first title that is on the page: a stand-in never glows beside the real row.
        if (t.best() != rank) return@LaunchedEffect
        try {
            val c = coords.value
            if (!t.scrolled) {
                t.scrolled = true
                if (c != null && c.isAttached && scroll != null) {
                    val screen = c.findRootCoordinates().size.height
                    val middle = c.positionInRoot().y + c.size.height / 2f
                    scroll.animateScrollBy(middle - screen / 2f)
                }
            }
            glow.animateTo(1f, tween(220))
            delay(700)
            glow.animateTo(0f, tween(1000))
        } finally {
            // Shown, or left before it finished: either way it is not waiting for this page any more.
            SettingsSpotlight.finish(t)
        }
    }

    this
        .onGloballyPositioned { coords.value = it }
        .drawBehind { if (glow.value > 0f) drawRect(Color.White.copy(alpha = 0.22f * glow.value)) }
}
