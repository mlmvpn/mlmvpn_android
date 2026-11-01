package com.mlmvpn.scanner.utils

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager

/**
 * Performance mode: the app's answer to "it hangs on my old phone".
 *
 * A low-end device is detected three ways and any one is enough:
 *
 *  - [ActivityManager.isLowRamDevice] — the system's own verdict (the device advertises itself
 *    as low-RAM, typically 2 GB or less);
 *  - a memory class of 128 MB or below, for devices that do not set the flag but are still small;
 *  - the system's "remove animations" accessibility setting (animator duration scale of 0) —
 *    a user who turned that on has already told every app the device is slow.
 *
 * In performance mode the expensive decoration is switched off where it runs: the drifting
 * lights draw one static frame, the wallpaper renders at half resolution instead of full, the
 * per-frame platform blur is replaced by the render-small path, the icon jiggle stops, and the
 * launch intro is skipped. None of these change what the app DOES — only what it draws.
 *
 * The user can also force the mode on or off from Settings > Display > Performance mode; "auto"
 * is the default and means "detect". The manual setting wins over detection in both directions.
 */
object Perf {

    const val MODE_AUTO = "auto"
    const val MODE_ON = "on"
    const val MODE_OFF = "off"

    private const val KEY_MODE = "perf_mode"

    /**
     * Devices at or below this memory class (MB) count as low-end even without the low-RAM
     * flag: the flag is set by the vendor and is often missing on exactly the old phones that
     * need this mode.
     */
    private const val LOW_MEMORY_CLASS_MB = 128

    /** The chosen mode, mirrored as Compose state so the Settings toggle repaints on change. */
    var mode by mutableStateOf(MODE_AUTO)
        private set

    /** The detected answer for this device, computed once — it cannot change while the app runs. */
    @Volatile private var detectedLowEnd: Boolean? = null

    fun init(context: Context) {
        mode = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO
    }

    fun setMode(context: Context, value: String) {
        val v = if (value == MODE_ON || value == MODE_OFF) value else MODE_AUTO
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(KEY_MODE, v).apply()
        mode = v
    }

    /** Force a mode from the toggle: on means performance mode, off means full effects. */
    fun setEnabled(context: Context, enabled: Boolean) {
        setMode(context, if (enabled) MODE_ON else MODE_OFF)
    }

    /**
     * Whether the app should run in performance mode right now: the manual choice when one is
     * set, otherwise the detection.
     */
    fun isLowEnd(context: Context): Boolean = when (mode) {
        MODE_ON -> true
        MODE_OFF -> false
        else -> detectedLowEnd ?: detect(context).also { detectedLowEnd = it }
    }

    private fun detect(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) return false
        if (am.isLowRamDevice) return true
        if (am.memoryClass <= LOW_MEMORY_CLASS_MB) return true
        // The user asked the SYSTEM to remove animations; honour it here too.
        val animationsOff = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (e: Exception) {
            false
        }
        return animationsOff
    }
}
