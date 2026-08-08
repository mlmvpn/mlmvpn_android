package kittoku.mvc.preference.accessor

import android.content.SharedPreferences
import kittoku.mvc.preference.MvcPreference


internal fun getSetPrefValue(key: MvcPreference, prefs: SharedPreferences): Set<String> {
    val defaultValue = when (key) {
        MvcPreference.SSL_SUITES -> setOf<String>()
        else -> throw NotImplementedError()
    }

    return prefs.getStringSet(key.name, defaultValue)!!
}

internal fun setSetPrefValue(value: Set<String>, key: MvcPreference, prefs: SharedPreferences) {
    prefs.edit().also {
        it.putStringSet(key.name, value)
        it.apply()
    }
}
