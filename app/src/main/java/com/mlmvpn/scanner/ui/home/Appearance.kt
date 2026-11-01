package com.mlmvpn.scanner.ui.home

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager

/**
 * The three things Settings > Display controls beyond the wallpaper itself.
 *
 * Each one is mirrored as Compose state for the same reason [Wallpapers.current] is: they are
 * chosen from a page several levels deep in the navigation but read at the activity root and on
 * the home screen, and a composable cannot observe a SharedPreferences write.
 */
object Appearance {

    const val THEME_AUTO = "auto"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private const val KEY_ICON_SCALE = "home_icon_scale"
    private const val KEY_THEME = "app_theme"
    private const val KEY_WALLPAPER_EVERYWHERE = "wallpaper_everywhere"
    private const val KEY_DOCK_LABELS = "home_dock_labels"
    private const val KEY_TEXT_SCALE = "app_text_scale"
    private const val KEY_TEXT_SCALE_AUTO = "app_text_scale_auto"
    private const val KEY_LABEL_SCALE = "home_label_scale"
    private const val KEY_HOME_WIDGET = "home_connection_widget"

    /** Percent. 100 is the size the grid picks for itself from the space available. */
    const val SCALE_MIN = 60
    const val SCALE_MAX = 140

    var iconScale by mutableStateOf(100)
        private set

    var theme by mutableStateOf(THEME_DARK)
        private set

    /**
     * Whether screens other than the home screen sit on the wallpaper.
     *
     * Off is not merely cosmetic: it is the way back to a plain, high-contrast background for
     * anyone who finds content over a backdrop hard to read, without giving up the wallpaper on
     * the home screen where there is nothing to read over it.
     */
    var wallpaperEverywhere by mutableStateOf(true)
        private set

    /**
     * Whether the four dock icons carry their captions.
     *
     * Off by default because that is what the dock is: four icons you learn by shape, and four
     * captions turn a bubble into a toolbar. It is a preference rather than a rule because the
     * shapes are only obvious once you already know them.
     */
    var dockLabels by mutableStateOf(false)
        private set

    /**
     * Whether text follows the phone's own font-size setting instead of [textScale].
     *
     * On by default, and it is the right default: someone who has enlarged text system-wide has
     * usually done so because they need it, and an app that quietly ignores that is an app they
     * cannot read. The manual slider is for people who want this app specifically to differ.
     */
    var textScaleAuto by mutableStateOf(true)
        private set

    /** Percent, applied to every piece of text in the app including Settings. */
    var textScale by mutableStateOf(100)
        private set

    /**
     * Percent, applied to the captions under the home icons and the dock.
     *
     * Relative to [textScale] rather than independent of it: the icon captions are text like any
     * other, so raising the app-wide size raises them too, and this then adjusts them on top. Two
     * absolute controls fighting over the same label would be the confusing arrangement.
     */
    var labelScale by mutableStateOf(100)
        private set

    /**
     * Whether the connection widget sits at the top of the home board (HomeConnectionWidget).
     *
     * On by default: it is the answer to "am I connected, through what, and where" the app's home
     * screen otherwise gives one lamp at a time. A user who removes it from the board turns this
     * off; Settings > Display and Widget Studio turn it back on.
     */
    var homeWidget by mutableStateOf(true)
        private set

    /** Seeds everything from disk. Called once, at activity start. */
    fun init(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        iconScale = prefs.getInt(KEY_ICON_SCALE, 100).coerceIn(SCALE_MIN, SCALE_MAX)
        // Dark stays the default. The app has been dark-only until now, so "automatic" would
        // have silently switched existing users to a light theme the older screens cannot render.
        theme = prefs.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
        wallpaperEverywhere = prefs.getBoolean(KEY_WALLPAPER_EVERYWHERE, true)
        dockLabels = prefs.getBoolean(KEY_DOCK_LABELS, false)
        textScaleAuto = prefs.getBoolean(KEY_TEXT_SCALE_AUTO, true)
        textScale = prefs.getInt(KEY_TEXT_SCALE, 100).coerceIn(SCALE_MIN, SCALE_MAX)
        labelScale = prefs.getInt(KEY_LABEL_SCALE, 100).coerceIn(SCALE_MIN, SCALE_MAX)
        homeWidget = prefs.getBoolean(KEY_HOME_WIDGET, true)
    }

    fun setHomeWidget(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(KEY_HOME_WIDGET, enabled).apply()
        homeWidget = enabled
    }

    fun setIconScale(context: Context, percent: Int) {
        val clamped = percent.coerceIn(SCALE_MIN, SCALE_MAX)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt(KEY_ICON_SCALE, clamped).apply()
        iconScale = clamped
    }

    fun setTheme(context: Context, value: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(KEY_THEME, value).apply()
        theme = value
    }

    fun setWallpaperEverywhere(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(KEY_WALLPAPER_EVERYWHERE, enabled).apply()
        wallpaperEverywhere = enabled
    }

    fun setDockLabels(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(KEY_DOCK_LABELS, enabled).apply()
        dockLabels = enabled
    }

    fun setTextScaleAuto(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(KEY_TEXT_SCALE_AUTO, enabled).apply()
        textScaleAuto = enabled
    }

    fun setTextScale(context: Context, percent: Int) {
        val clamped = percent.coerceIn(SCALE_MIN, SCALE_MAX)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt(KEY_TEXT_SCALE, clamped).apply()
        textScale = clamped
    }

    fun setLabelScale(context: Context, percent: Int) {
        val clamped = percent.coerceIn(SCALE_MIN, SCALE_MAX)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putInt(KEY_LABEL_SCALE, clamped).apply()
        labelScale = clamped
    }

    /** The resolved answer, with "automatic" turned into the phone's current setting. */
    @Composable
    fun isDark(): Boolean = when (theme) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
}
