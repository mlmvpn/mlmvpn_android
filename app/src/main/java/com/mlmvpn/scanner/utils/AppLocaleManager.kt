package com.mlmvpn.scanner.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

object AppLocaleManager {
    private const val PREFS_NAME = "app_locale_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    private val _currentLanguage = MutableStateFlow("auto")
    val currentLanguage: StateFlow<String> = _currentLanguage

    fun init(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _currentLanguage.value = prefs.getString(KEY_LANGUAGE, "auto") ?: "auto"
    }

    fun setLanguage(context: Context, languageCode: String) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
        _currentLanguage.value = languageCode
    }

    fun getResolvedLocale(): Locale {
        val selected = _currentLanguage.value
        if (selected == "auto") {
            return Locale.getDefault()
        }
        return Locale(selected)
    }

    fun wrapContext(context: Context): Context {
        val res = context.resources
        val configuration = Configuration(res.configuration)
        val newLocale = getResolvedLocale()
        configuration.setLocale(newLocale)
        configuration.setLayoutDirection(newLocale)
        return context.createConfigurationContext(configuration)
    }
}

@Composable
fun LocaleProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val currentLanguage by AppLocaleManager.currentLanguage.collectAsState()
    
    val resolvedLocale = AppLocaleManager.getResolvedLocale()
    val configuration = Configuration(LocalConfiguration.current)
    configuration.setLocale(resolvedLocale)
    configuration.setLayoutDirection(resolvedLocale)
    
    val layoutDirection = if (resolvedLocale.language == "fa" || resolvedLocale.language == "ar") {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    val registryOwner = androidx.activity.compose.LocalActivityResultRegistryOwner.current

    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides layoutDirection
    ) {
        if (registryOwner != null) {
            CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides registryOwner
            ) {
                content()
            }
        } else {
            content()
        }
    }
}
