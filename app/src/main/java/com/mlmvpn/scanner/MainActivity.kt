package com.mlmvpn.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

import androidx.appcompat.app.AppCompatActivity

import android.util.Log

import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.activity.enableEdgeToEdge

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        com.mlmvpn.scanner.utils.AppLocaleManager.init(newBase)
        super.attachBaseContext(com.mlmvpn.scanner.utils.AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d("LanguageSwitch", "MainActivity.onCreate called")
        com.mlmvpn.scanner.utils.AppLocaleManager.init(this)
        
        com.mlmvpn.scanner.engines.subgenerator.SubGenManager(this).startAutoSync()

        setContent {
            val colorScheme = androidx.compose.material3.darkColorScheme(
                primary = androidx.compose.ui.graphics.Color(0xFF8AB4F8),
                onPrimary = androidx.compose.ui.graphics.Color(0xFF202124),
                primaryContainer = androidx.compose.ui.graphics.Color(0xFF8AB4F8).copy(alpha = 0.2f),
                onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF8AB4F8),
                surface = androidx.compose.ui.graphics.Color(0xFF202124),
                onSurface = androidx.compose.ui.graphics.Color(0xFFE8EAED),
                background = androidx.compose.ui.graphics.Color(0xFF121212),
                onBackground = androidx.compose.ui.graphics.Color(0xFFE8EAED)
            )
            MaterialTheme(colorScheme = colorScheme) {
                val systemBars = androidx.compose.foundation.layout.WindowInsets.systemBars.asPaddingValues()
                androidx.compose.runtime.CompositionLocalProvider(
                    com.mlmvpn.scanner.ui.LocalSystemBottomPadding provides systemBars.calculateBottomPadding(),
                    com.mlmvpn.scanner.ui.LocalSystemTopPadding provides systemBars.calculateTopPadding()
                ) {
                    com.mlmvpn.scanner.utils.LocaleProvider {
                        Surface(
                            modifier = Modifier.fillMaxSize().systemBarsPadding(),
                            color = androidx.compose.ui.graphics.Color(0xFF121212)
                        ) {
                            // AppScreen is composed underneath from the start, so the tab is
                            // already warm and its first fetch already in flight by the time
                            // the intro clears — the animation costs no perceived delay.
                            val splashDone = androidx.compose.runtime.remember {
                                androidx.compose.runtime.mutableStateOf(false)
                            }
                            com.mlmvpn.scanner.ui.AppScreen()
                            if (!splashDone.value) {
                                com.mlmvpn.scanner.ui.SplashScreen(
                                    onFinished = { splashDone.value = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("LanguageSwitch", "MainActivity.onConfigurationChanged called. New Locale: ${newConfig.locales}")
    }
}
