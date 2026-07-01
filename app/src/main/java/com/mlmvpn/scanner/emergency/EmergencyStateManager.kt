package com.mlmvpn.scanner.emergency

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EmergencyStateManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("emergency_prefs", Context.MODE_PRIVATE)

    private val _isVercelEnabled = MutableStateFlow(prefs.getBoolean(KEY_VERCEL_ENABLED, false))
    val isVercelEnabled: StateFlow<Boolean> = _isVercelEnabled.asStateFlow()

    fun setVercelEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VERCEL_ENABLED, enabled).apply()
        _isVercelEnabled.value = enabled
    }

    companion object {
        private const val KEY_VERCEL_ENABLED = "vercel_enabled"

        @Volatile
        private var INSTANCE: EmergencyStateManager? = null

        fun getInstance(context: Context): EmergencyStateManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EmergencyStateManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
