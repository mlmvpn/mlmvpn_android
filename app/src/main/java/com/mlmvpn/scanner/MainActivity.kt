package com.mlmvpn.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import android.util.Log

import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        com.mlmvpn.scanner.utils.AppLocaleManager.init(newBase)
        super.attachBaseContext(com.mlmvpn.scanner.utils.AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app's UI is always dark (darkColorScheme below is hardcoded, it never follows the
        // system light/dark setting) -- but enableEdgeToEdge() with no args picks status/nav bar
        // ICON color from the SYSTEM's light/dark setting, not from what the app actually draws.
        // A phone in system light mode got dark status-bar icons over the app's dark background:
        // invisible clock/battery. Forcing SystemBarStyle.dark(...) always gives light icons,
        // which is correct either way since the background here never changes.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        Log.d("LanguageSwitch", "MainActivity.onCreate called")
        com.mlmvpn.scanner.utils.AppLocaleManager.init(this)
        // Seeds the wallpaper choice before the first frame, so the backdrop the user picked is
        // the one they see on launch rather than a flash of the default.
        com.mlmvpn.scanner.ui.home.Wallpapers.init(this)
        com.mlmvpn.scanner.ui.home.Appearance.init(this)

        // The subscription sync only matters once the user has Cloudflare accounts, so it no
        // longer starts on the launch path: building its managers on the main thread before the
        // first frame was a measurable jank on old phones. Two seconds in, off the critical path.
        lifecycleScope.launch(Dispatchers.IO) {
            delay(2000)
            com.mlmvpn.scanner.engines.subgenerator.SubGenManager(this@MainActivity).startAutoSync()
        }

        setContent {
            // Settings > Display > Appearance drives this. Note the honest limit: only the
            // rebuilt surfaces (the home screen, Settings and everything under it, Advanced VPN,
            // Total Usage) resolve their colours through the theme. The older screens still carry
            // hardcoded dark hex values and stay dark until each is converted -- which is why the
            // setting defaults to Dark rather than Automatic.
            val darkTheme = com.mlmvpn.scanner.ui.home.Appearance.isDark()
            val colorScheme = if (darkTheme) {
                androidx.compose.material3.darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFFC4B0FF),
                    onPrimary = androidx.compose.ui.graphics.Color(0xFF202124),
                    primaryContainer = androidx.compose.ui.graphics.Color(0xFFC4B0FF).copy(alpha = 0.2f),
                    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFC4B0FF),
                    surface = androidx.compose.ui.graphics.Color(0xFF202124),
                    onSurface = androidx.compose.ui.graphics.Color(0xFFE8EAED),
                    background = androidx.compose.ui.graphics.Color(0xFF121212),
                    onBackground = androidx.compose.ui.graphics.Color(0xFFE8EAED)
                )
            } else {
                // Still a DARK scheme, with lifted surfaces. Text across the app is white in
                // both appearances, so a light scheme here would hand Material components black
                // type on surfaces the rest of the app draws white type on.
                androidx.compose.material3.darkColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF8B5CF6),
                    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                    primaryContainer = androidx.compose.ui.graphics.Color(0xFF8B5CF6).copy(alpha = 0.25f),
                    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                    surface = androidx.compose.ui.graphics.Color(0xFF2C2C2E),
                    onSurface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
                    background = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
                    onBackground = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
                )
            }
            // IRANSansX for the whole app. Material's Typography has no "default family" field,
            // so every style is rebuilt with it -- see ui/theme/Type.kt.
            MaterialTheme(
                colorScheme = colorScheme,
                typography = com.mlmvpn.scanner.ui.theme.iranSansTypography(
                    androidx.compose.material3.MaterialTheme.typography
                ),
            ) {
                val systemBars = androidx.compose.foundation.layout.WindowInsets.systemBars.asPaddingValues()
                // The app-wide text size rides on the DENSITY's fontScale rather than on the
                // Typography. It has to: nearly every Text in this app passes an explicit
                // `fontSize = 14.sp`, and an explicit size overrides the type scale completely.
                // fontScale multiplies every sp in the tree, so hardcoded sizes scale as well.
                val baseDensity = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    com.mlmvpn.scanner.ui.LocalSystemBottomPadding provides systemBars.calculateBottomPadding(),
                    com.mlmvpn.scanner.ui.LocalSystemTopPadding provides systemBars.calculateTopPadding(),
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        density = baseDensity.density,
                        // Following the system means leaving its fontScale exactly alone,
                        // not multiplying it by 100%.
                        fontScale = if (com.mlmvpn.scanner.ui.home.Appearance.textScaleAuto) {
                            baseDensity.fontScale
                        } else {
                            baseDensity.fontScale *
                                (com.mlmvpn.scanner.ui.home.Appearance.textScale / 100f)
                        },
                    ),
                ) {
                    com.mlmvpn.scanner.utils.LocaleProvider {
                        // No systemBarsPadding() here on purpose. The home screen's backdrop has
                        // to run edge to edge -- under the clock and signal icons at the top and
                        // under the navigation bar at the bottom -- so that the app opens with a
                        // full-bleed wallpaper and no cut line across it. Insetting at the root
                        // would letterbox the wallpaper into a black-topped strip.
                        //
                        // The inset is applied one level down instead, by AppScreen's feature
                        // host. Full-screen overlays (the emergency screens, About, Help, the
                        // anti-sanction screen) already apply LocalSystemTopPadding themselves,
                        // so they keep working -- and stop being padded twice, which is what was
                        // quietly happening while the root inset was also in play.
                        var windowSizePx by androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero)
                        }
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { windowSizePx = it }
                                .background(
                                    if (darkTheme) androidx.compose.ui.graphics.Color(0xFF121212)
                                    else androidx.compose.ui.graphics.Color(0xFF1C1C1E)
                                )
                        ) {
                            androidx.compose.runtime.CompositionLocalProvider(
                                com.mlmvpn.scanner.ui.LocalWindowSizePx provides windowSizePx
                            ) {
                            com.mlmvpn.scanner.ui.home.HomeWallpaper(
                                wallpaperId = com.mlmvpn.scanner.ui.home.Wallpapers.current,
                                blurPercent = com.mlmvpn.scanner.ui.home.Wallpapers.blur,
                            )
                            // AppScreen is composed underneath from the start, so the tab is
                            // already warm and its first fetch already in flight by the time
                            // the intro clears — the animation costs no perceived delay.
                            val splashDone = androidx.compose.runtime.remember {
                                androidx.compose.runtime.mutableStateOf(
                                    com.mlmvpn.scanner.ui.IntroState.played
                                )
                            }
                            com.mlmvpn.scanner.ui.AppScreen()
                            if (!splashDone.value) {
                                com.mlmvpn.scanner.ui.SplashScreen(
                                    onFinished = {
                                        splashDone.value = true
                                        com.mlmvpn.scanner.ui.IntroState.played = true
                                    }
                                )
                            }
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
