package kittoku.mvc.preference.accessor

import android.content.SharedPreferences
import kittoku.mvc.preference.MvcPreference


internal fun getStringPrefValue(key: MvcPreference, prefs: SharedPreferences): String {
    val defaultValue = when (key) {
        MvcPreference.HOME_HOSTNAME,
        MvcPreference.HOME_USERNAME,
        MvcPreference.HOME_PASSWORD,
        MvcPreference.HOME_HUB,
        MvcPreference.HOME_STATUS,
        MvcPreference.ETHERNET_MAC_ADDRESS,
        MvcPreference.LOG_DIRECTORY -> ""
        MvcPreference.SSL_VERSION -> "DEFAULT"
        else -> throw NotImplementedError()
    }

    return prefs.getString(key.name, defaultValue)!!
}

internal fun setStringPrefValue(value: String, key: MvcPreference, prefs: SharedPreferences) {
    prefs.edit().also {
        it.putString(key.name, value)
        it.apply()
    }
}
