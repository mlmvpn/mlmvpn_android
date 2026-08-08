package kittoku.mvc.preference.accessor

import android.content.SharedPreferences
import kittoku.mvc.preference.MvcPreference
import kittoku.mvc.unit.ETHERNET_MAX_MTU


internal fun getIntPrefValue(key: MvcPreference, prefs: SharedPreferences): Int {
    val defaultValue = when (key) {
        MvcPreference.SSL_PORT -> 443
        MvcPreference.ETHERNET_MTU -> ETHERNET_MAX_MTU
        else -> throw NotImplementedError()
    }

    return prefs.getString(key.name, null)?.toIntOrNull() ?: defaultValue
}

internal fun setIntPrefValue(value: Int, key: MvcPreference, prefs: SharedPreferences) {
    prefs.edit().also {
        it.putString(key.name, value.toString())
        it.apply()
    }
}
