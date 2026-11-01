package com.mlmvpn.scanner.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mlmvpn.scanner.ui.home.Appearance
import com.mlmvpn.scanner.ui.settings.Ios

// =================================================================================================
// The app-wide palette.
//
// These names date from the original dark-only design, where each was a fixed hex value. They are
// kept -- there are more than fourteen hundred uses of them across seventeen screens -- but they
// are no longer constants. Each now resolves through the same iOS palette Settings uses, so a
// screen written years ago picks up the glass look and the Appearance setting without a single
// edit to its own file.
//
// Two rules run through all of it:
//
// 1. TEXT IS WHITE IN BOTH APPEARANCES. Every surface in the app now sits on the wallpaper, and
//    white is the only colour that reads against both a dark preset and a bright photo. The
//    consequence is deliberate and worth stating: "light" here means lighter GLASS, not a white
//    sheet. A white sheet with white text would be unreadable, so even the no-wallpaper fallback
//    stays dark enough to carry white type.
//
// 2. SURFACES ARE TRANSLUCENT, NOT OPAQUE. What used to be a solid card is now a wash that lets
//    the backdrop through. That is what makes a screen written for the old design line up with
//    the home screen and Settings without being rewritten.
// =================================================================================================

/** Page and inner-panel backgrounds. Deeper than a card, so nested boxes still read as nested. */
val BgDark: Color
    @Composable get() = if (Appearance.isDark()) {
        Color.Black.copy(alpha = 0.26f)
    } else {
        Color.Black.copy(alpha = 0.13f)
    }

/** Cards and raised panels. The same glass value the Settings groups use. */
val SurfaceDark: Color
    @Composable get() = Ios.CardOverWallpaper

/** Dialogs and modal sheets. See [Ios.DialogSurface] for why this is not [SurfaceDark]. */
val DialogSurface: Color
    @Composable get() = Ios.DialogSurface

/** Hairlines and outlines. Light on glass rather than dark on a sheet. */
val BorderDark: Color
    @Composable get() = if (Appearance.isDark()) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.White.copy(alpha = 0.24f)
    }

val Primary: Color
    @Composable get() = Ios.Blue

val PrimaryLight: Color
    @Composable get() = Color(0xFFAECBFA)

val TextPrimary: Color
    @Composable get() = Ios.Label

val TextMuted: Color
    @Composable get() = Ios.SecondaryLabel

/** The faintest readable tier: timestamps, hints, disabled captions. */
val TextDim: Color
    @Composable get() = Color.White.copy(alpha = 0.45f)

val GreenOk: Color
    @Composable get() = Ios.Green

val RedError: Color
    @Composable get() = Ios.Red

val YellowWarn: Color
    @Composable get() = Ios.Yellow

val ConnectedRed: Color
    @Composable get() = Ios.Pink

// The Material colour scheme is built outside composition, so it cannot read the values above and
// keeps its own literals. It backs everything Material draws for itself: a DropdownMenu's popup
// surface, a DatePicker, a ripple. Those were still the old Google-console palette, which is why
// a menu opened as a grey #202124 card with a Google-blue highlight in the middle of a black
// glass app -- the "ugly, unprofessional" region picker. These are the same values the composable
// tokens resolve to in dark mode, so a Material surface now matches a hand-styled one.
private val SchemeBg = Color(0xFF0A0614)
private val SchemeSurface = Color(0xFF1B1330)
private val SchemeBorder = Color(0xFF3D2F5C)
private val SchemePrimary = Color(0xFF9D7BFF)
private val SchemeOnSurface = Color(0xFFFFFFFF)

private val DarkColorScheme = darkColorScheme(
    primary            = SchemePrimary,
    onPrimary          = SchemeBg,
    background         = SchemeBg,
    surface            = SchemeSurface,
    onBackground       = SchemeOnSurface,
    onSurface          = SchemeOnSurface,
    outline            = SchemeBorder,
    secondaryContainer = SchemeBorder,
)

@Composable
fun MlmVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
