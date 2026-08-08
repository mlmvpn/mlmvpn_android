package kittoku.mvc.preference.accessor

import android.content.SharedPreferences
import kittoku.mvc.preference.MvcPreference


internal fun getBooleanPrefValue(key: MvcPreference, prefs: SharedPreferences): Boolean {
    val defaultValue = when (key) {
        MvcPreference.HOME_CONNECTOR,
        MvcPreference.SSL_DO_SELECT_SUITES,
        MvcPreference.UDP_ENABLE_ACCELERATION,
        MvcPreference.LOG_DO_SAVE_LOG -> false
        else -> throw NotImplementedError()
    }

    return prefs.getBoolean(key.name, defaultValue)
}

internal fun setBooleanPrefValue(value: Boolean, key: MvcPreference, prefs: SharedPreferences) {
    prefs.edit().also {
        it.putBoolean(key.name, value)
        it.apply()
    }
}
