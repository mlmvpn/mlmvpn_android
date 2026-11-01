package com.mlmvpn.scanner.ui.settings

import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlmvpn.scanner.ui.home.Appearance
import com.mlmvpn.scanner.ui.home.fadingEdges
import com.mlmvpn.scanner.ui.home.frostedGlass

/**
 * The pieces an iOS Settings screen is made of: inset grouped cards, 29pt tinted glyph tiles,
 * separators that start after the glyph rather than at the card edge, and a value shown in grey
 * on the trailing side before the chevron.
 *
 * The numbers here are the ones iOS actually uses (29pt icon, 7pt corner, 44pt minimum row
 * height, separator inset past the icon). They are what make a list read as "Settings" rather
 * than as a generic list with rounded corners, so they are written down rather than eyeballed.
 */
object Ios {

    /**
     * Every colour resolves against the app's Appearance setting rather than being a constant,
     * which is what lets Settings > Display switch the redesigned screens between light and dark
     * without each of them knowing about the setting.
     *
     * The values are iOS's own for each mode -- label, secondaryLabel, separator, the grouped
     * background and card, and the system accent set. Guessing at them is what makes a list look
     * "iOS-ish" rather than iOS.
     */
    /**
     * The fallback sheet, used only where there is no wallpaper to sit on.
     *
     * Note what "light" is NOT: a white page. Text across the app is white in both appearances,
     * because every surface now sits on the wallpaper and white is the only colour that reads
     * against both a dark preset and a bright photo. A white sheet under white type would be
     * unreadable, so light is a lighter DARK here -- and the difference the setting actually
     * makes is in the glass, which lifts considerably.
     */
    val Background: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFF000000) else Color(0xFF1C1C1E)

    val Card: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFF1C1C1E) else Color(0xFF2C2C2E)

    /**
     * What the grouped cards actually use. These screens sit ON the home wallpaper rather than on
     * a flat sheet, so a solid card would punch an opaque hole through it. Slightly translucent
     * keeps the backdrop present at almost no cost in contrast.
     */
    val CardOverWallpaper: Color
        @Composable get() = if (Appearance.isDark()) {
            Color(0xFF1B1330).copy(alpha = 0.82f)
        } else {
            Color(0xFF3A2D5C).copy(alpha = 0.62f)
        }

    /**
     * Dialogs and modal sheets.
     *
     * Denser than [CardOverWallpaper] on purpose. A card is laid on the wallpaper, which is
     * blurred and still, so translucency there costs nothing. A dialog floats over the live
     * screen -- a node list, a running scan -- and at card opacity that content reads straight
     * through the text sitting on it. Near-opaque, with just enough left to read as glass.
     */
    val DialogSurface: Color
        @Composable get() = if (Appearance.isDark()) {
            Color(0xFF1C1C1E).copy(alpha = 0.97f)
        } else {
            Color(0xFF3A3A3C).copy(alpha = 0.95f)
        }

    val Separator: Color
        @Composable get() = if (Appearance.isDark()) {
            Color.White.copy(alpha = 0.13f)
        } else {
            Color.White.copy(alpha = 0.22f)
        }

    /** White in both appearances. See [Background] for why. */
    val Label: Color
        @Composable get() = Color(0xFFFFFFFF)

    val SecondaryLabel: Color
        @Composable get() = if (Appearance.isDark()) {
            Color.White.copy(alpha = 0.62f)
        } else {
            Color.White.copy(alpha = 0.74f)
        }

    val Chevron: Color
        @Composable get() = if (Appearance.isDark()) {
            Color.White.copy(alpha = 0.32f)
        } else {
            Color.White.copy(alpha = 0.45f)
        }

    val Destructive: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFFFF453A) else Color(0xFFFF3B30)

    val Blue: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFF9D7BFF) else Color(0xFF6D4AFF)
    val Green: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFF32D74B) else Color(0xFF34C759)
    val Indigo: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFF5E5CE6) else Color(0xFF5856D6)
    val Orange: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    val Pink: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFFFF375F) else Color(0xFFFF2D55)
    val Purple: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFFBF5AF2) else Color(0xFFAF52DE)
    val Red: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFFFF453A) else Color(0xFFFF3B30)
    val Teal: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFF64D2FF) else Color(0xFF32ADE6)
    val Yellow: Color
        @Composable get() = if (Appearance.isDark()) Color(0xFFFFD60A) else Color(0xFFFFCC00)
    val Gray: Color
        @Composable get() = Color(0xFF8E8E93)

    /** Cloudflare's orange. A brand colour, so it is the same in both modes. */
    val CloudflareOrange = Color(0xFFF6821F)
}

/**
 * iOS 26 rounds its grouped cards hard enough that a single-row card reads almost as a pill.
 * 10dp -- the usual Material figure -- looked visibly squarer than the reference next to it.
 */
private val RowShape = RoundedCornerShape(22.dp)
private val GlyphShape = RoundedCornerShape(8.dp)

/** Section caption above a card, in iOS's small grey uppercase-ish style. */
@Composable
fun SettingsSectionHeader(text: String, horizontal: androidx.compose.ui.unit.Dp = 20.dp) {
    Text(
        text = text,
        color = Ios.SecondaryLabel,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = horizontal, end = horizontal, top = 26.dp, bottom = 7.dp),
    )
}

/**
 * One inset grouped card. Children are rows; separators between them are drawn by the card so a
 * row never has to know whether it is the last one.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    horizontal: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable SettingsGroupScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontal)
            .frostedGlass(RowShape),
    ) {
        SettingsGroupScopeImpl(this).content()
    }
}

interface SettingsGroupScope {
    @Composable fun Separator()
}

private class SettingsGroupScopeImpl(
    private val column: androidx.compose.foundation.layout.ColumnScope,
) : SettingsGroupScope {
    @Composable
    override fun Separator() {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Starts past the glyph, exactly as iOS does: 16 padding + 29 icon + 12 gap.
                .padding(start = 57.dp)
                .height(0.5.dp)
                .background(Ios.Separator)
        )
    }
}

/** The tinted rounded-square glyph on the leading edge of a row. */
@Composable
fun SettingsGlyph(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(29.dp)
            .clip(GlyphShape)
            .background(
                Brush.verticalGradient(
                    listOf(lerp(tint, Color.White, 0.10f), lerp(tint, Color.Black, 0.10f))
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RowChevron() {
    Icon(
        Icons.Default.ChevronRight,
        contentDescription = null,
        tint = Ios.Chevron,
        modifier = Modifier
            .size(18.dp)
            // The chevron always points the way navigation goes, which is leftward in Persian.
            .scale(
                scaleX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f,
                scaleY = 1f,
            ),
    )
}

@Composable
private fun RowFrame(
    icon: ImageVector?,
    tint: Color,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
    label: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsGlyph(icon, tint)
            Spacer(Modifier.width(12.dp))
        }
        Box(modifier = Modifier.weight(1f)) { label() }
        trailing()
    }
}

/**
 * A row that IS an action rather than a value: label, leading glyph, optional spinner.
 *
 * Sits inside a [SettingsGroup] alongside ordinary rows, which is
 * how iOS puts "Check for Updates" at the bottom of a card of values.
 *
 * WHITE by default, not the accent. iOS tints these blue, but a card of white rows with two blue
 * ones at the bottom pulls the eye to whichever action happens to be last rather than to what the
 * card is about. Colour here means STATE -- a relay's health, a latency band, a destructive
 * action -- so an ordinary action is white like the rows above it, and the ones that pass a tint
 * (orange to authorize, red to delete) are the ones actually saying something.
 */
@Composable
fun SettingsActionRow(
    label: String,
    icon: ImageVector,
    tint: Color = Ios.Label,
    /**
     * The label's colour, when it should not follow the glyph.
     *
     * A destructive action reads red in both, which is the platform idiom. An attention cue --
     * "this is the step you are missing" -- belongs on the glyph alone: a coloured sentence is
     * decoration, and colour on this screen means state.
     */
    labelColor: Color? = null,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val live = enabled && !busy
    val shade = if (live) tint else Ios.SecondaryLabel
    val words = if (live) (labelColor ?: tint) else Ios.SecondaryLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (live) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = shade, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = words, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = shade,
                strokeWidth = 2.dp,
            )
        }
    }
}

/** The grey explanatory paragraph iOS puts under a group. */
@Composable
fun SettingsFooter(text: String) {
    Text(
        text,
        color = Ios.SecondaryLabel,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
}

/** A row that navigates: label, optional grey value, chevron. */
@Composable
fun SettingsRow(
    title: String,
    icon: ImageVector? = null,
    tint: Color = Ios.Gray,
    value: String? = null,
    subtitle: String? = null,
    titleColor: Color = Ios.Label,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    /** iOS's red count badge before the chevron ("1" on Software Update). 0 draws nothing. */
    badge: Int = 0,
) {
    RowFrame(
        icon = icon,
        tint = tint,
        onClick = onClick,
        trailing = {
            if (badge > 0) {
                IosBadge(badge, modifier = Modifier.padding(end = if (showChevron) 6.dp else 0.dp))
            }
            if (value != null) {
                Text(
                    value,
                    color = Ios.SecondaryLabel,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = if (showChevron) 6.dp else 0.dp),
                )
            }
            if (showChevron) RowChevron()
        },
        label = {
            Column {
                Text(title, color = titleColor, fontSize = 16.sp)
                if (subtitle != null) {
                    Text(subtitle, color = Ios.SecondaryLabel, fontSize = 12.sp)
                }
            }
        },
    )
}

/** The red count capsule iOS draws on a row or an icon that has something waiting. */
@Composable
fun IosBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 22.dp)
            .widthIn(min = 22.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFFF3B30))
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
    }
}

/** A row whose control is a switch. Applies immediately -- iOS Settings has no Save button. */
@Composable
fun SettingsToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    tint: Color = Ios.Gray,
    subtitle: String? = null,
) {
    RowFrame(
        icon = icon,
        tint = tint,
        onClick = null,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    // The app's own blue rather than the platform green: green is Apple's accent,
                    // not ours, and it was the one control on screen not speaking the app's colour.
                    checkedTrackColor = Ios.Blue,
                    checkedBorderColor = Ios.Blue,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF39393D),
                    uncheckedBorderColor = Color(0xFF39393D),
                ),
                modifier = Modifier.scale(0.85f),
            )
        },
        label = {
            Column {
                Text(title, color = Ios.Label, fontSize = 16.sp)
                if (subtitle != null) {
                    Text(subtitle, color = Ios.SecondaryLabel, fontSize = 12.sp)
                }
            }
        },
    )
}

/**
 * A row whose value is typed in place, the way iOS handles manual IP entry: the field sits on
 * the trailing side and looks like the grey value text until it is focused.
 *
 * `error` turns the value red and shows the reason underneath rather than blocking the keystroke,
 * because refusing characters mid-typing makes a field impossible to correct.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsTextRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector? = null,
    tint: Color = Ios.Gray,
    placeholder: String = "",
    error: String? = null,
    numeric: Boolean = false,
) {
    // The whole row focuses the field. The field itself is 150dp wide on the trailing edge, so
    // aiming at "Global API Key" -- the obvious target, and the only part of the row that names
    // what you are about to type -- used to do nothing at all.
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column {
        RowFrame(
            icon = icon,
            tint = tint,
            onClick = {
                focus.requestFocus()
                keyboard?.show()
            },
            trailing = {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = if (error != null) Ios.Destructive else Ios.SecondaryLabel,
                        fontSize = 16.sp,
                        textAlign = TextAlign.End,
                    ),
                    cursorBrush = SolidColor(Ios.Blue),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) {
                            androidx.compose.ui.text.input.KeyboardType.Number
                        } else {
                            androidx.compose.ui.text.input.KeyboardType.Text
                        }
                    ),
                    modifier = Modifier.width(150.dp).focusRequester(focus),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterEnd) {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(
                                    placeholder,
                                    color = Ios.SecondaryLabel.copy(alpha = 0.5f),
                                    fontSize = 16.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
            },
            label = { Text(title, color = Ios.Label, fontSize = 16.sp) },
        )
        if (error != null) {
            Text(
                error,
                color = Ios.Destructive,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 57.dp, end = 16.dp, bottom = 10.dp),
            )
        }
    }
}

/**
 * The account card at the top of iOS Settings. Here it carries the Cloudflare account the app is
 * signed in to, because that is this app's equivalent: the thing that turns it from a client into
 * something that can deploy servers of its own.
 */
@Composable
fun CloudflareAccountCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * One short label per connected account. With more than one, a second row of overlapping
     * dots appears under the main row -- the same shape iOS uses for the Family Sharing members
     * under an Apple Account.
     */
    accountInitials: List<String> = emptyList(),
    accountsLabel: String? = null,
    onAccountsClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(RowShape),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Stands in for iOS's profile photo. A cloud glyph on Cloudflare's own orange reads as
        // the brand at this size without shipping a logo asset.
        // The real Cloudflare mark, not a generic cloud glyph on an orange disc. The artwork
        // carries its own ground and its own squircle, so nothing is drawn behind it -- the same
        // rule the home-screen tiles follow. See HomeApp.imageRes.
        Image(
            painter = painterResource(com.mlmvpn.scanner.R.drawable.ic_app_cloudflare),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Ios.Label,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = Ios.SecondaryLabel,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        RowChevron()
    }

    if (accountInitials.size > 1) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Starts past the avatar, the way iOS insets the divider in this card.
                .padding(start = 86.dp)
                .height(0.5.dp)
                .background(Ios.Separator)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onAccountsClick != null) Modifier.clickable(onClick = onAccountsClick)
                    else Modifier
                )
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountDots(accountInitials)
            Spacer(Modifier.width(12.dp))
            Text(
                accountsLabel ?: "",
                color = Ios.Label,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            RowChevron()
        }
    }
    }
}

/**
 * Overlapping initial dots, one per connected account.
 *
 * Laid out by hand rather than with `Arrangement.spacedBy(-x)`, which rejects negative spacing:
 * each dot is offset by its index, and the Box is sized to the resulting run so the label beside
 * it starts where the dots actually end rather than after a full-width row. `offset` is used
 * rather than `absoluteOffset` on purpose -- in Persian the stack should build from the right,
 * and the direction-aware version does that for free.
 */
@Composable
private fun AccountDots(initials: List<String>) {
    val shown = initials.take(4)
    val step = 17.dp
    val dot = 26.dp
    val palette = listOf(Ios.CloudflareOrange, Color(0xFF5AC8FA), Color(0xFF34C759), Color(0xFFAF52DE))

    Box(modifier = Modifier.height(dot).width(dot + step * (shown.size - 1))) {
        shown.forEachIndexed { index, label ->
            Box(
                modifier = Modifier
                    .offset(x = step * index)
                    .size(dot)
                    .clip(CircleShape)
                    // The ring is what separates one dot from the one it overlaps; without it a
                    // run of similar colours reads as a single blob.
                    .background(Ios.Card)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(palette[index % palette.size]),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.take(1).uppercase(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Detail pages
//
// iOS never opens a dropdown inside a settings list: a choice pushes a page, you pick, and the
// page pops. That is not decoration. It is what lets the list row show the CURRENT value in grey
// on its trailing edge, which is where you look to answer "what is this set to" without opening
// anything at all.
// ---------------------------------------------------------------------------------------------

/**
 * The frame every settings page uses: no background of its own, so the home wallpaper shows
 * through, plus an optional iOS navigation bar.
 *
 * Passing neither a title nor a back action gives a bare page. That is what the root Settings
 * screen uses, so nothing sits above the account card.
 */
@Composable
fun IosScreen(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    /**
     * iOS's large title: left-aligned, heavy, and part of the scrolling content rather than the
     * bar. A page uses this OR [title], not both -- the root of a settings tree gets the large
     * one and no back chevron, a pushed page gets the small centred one.
     */
    largeTitle: String? = null,
    modifier: Modifier = Modifier,
    /**
     * Off when the page supplies its OWN scrolling list. A LazyColumn measured inside a
     * vertically scrolling Column gets infinite height and crashes, so a page with a long list
     * (the app picker) turns this off and scrolls itself.
     */
    scrollable: Boolean = true,
    /**
     * The control on the trailing edge of the navigation bar -- iOS's "Edit" / "Select" slot.
     *
     * Optional, and absent on every page that only navigates. It exists for the lists that can be
     * acted on in bulk, where iOS puts the mode switch in the bar rather than in the content: a
     * button inside the scroll would leave the screen as soon as the user scrolled to the rows
     * they wanted to select.
     */
    trailing: (@Composable () -> Unit)? = null,
    /**
     * Pull down to reload. When set, the downward pull at the top of the page opens a spinner and
     * runs this instead of drifting the large title -- a page cannot mean two things by one gesture.
     * Works only on a page this scaffold scrolls ([scrollable] true); a page with its own list
     * attaches [rememberIosRefreshState] to that list instead.
     */
    onRefresh: (suspend () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val topInset = com.mlmvpn.scanner.ui.LocalContentTopInset.current
    val bottomInset = com.mlmvpn.scanner.ui.LocalSystemBottomPadding.current
    val hasBar = title != null || onBack != null || trailing != null
    val barHeight = if (hasBar) 44.dp else 0.dp
    val refresh = rememberIosRefreshState(onRefresh)

    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val maxPull = with(LocalDensity.current) { 96.dp.toPx() }

    // How far past the top the page has been pulled, in pixels.
    //
    // Held in a plain float rather than an Animatable. The previous version wrote to an Animatable
    // from the nested-scroll callbacks, which are not suspending, so every touch move had to
    // launch a coroutine to call snapTo -- and under a fast drag those queued up and arrived late.
    // That is what made the title move sometimes and not others. A plain state is written
    // synchronously on the frame the touch arrives; only the release is animated.
    var pull by remember { mutableFloatStateOf(0f) }
    // Whether a released pull keeps the title where it travelled to.
    var latched by remember { mutableStateOf(false) }

    val overscroll = remember(maxPull) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Scrolling back up gives the pull back first, and drops the latch: the title
                // returns to the leading edge as soon as the page starts moving under it.
                if (available.y < 0f && pull > 0f) {
                    val used = minOf(-available.y, pull)
                    pull -= used
                    latched = false
                    return Offset(0f, -used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y > 0f && scrollState.value == 0) {
                    // Damped, and damped harder the further it goes, so the travel eases into its
                    // limit instead of stopping dead against it.
                    val room = 1f - (pull / maxPull)
                    pull = (pull + available.y * 0.5f * room).coerceIn(0f, maxPull)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull <= 0f) return Velocity.Zero
                // Past halfway the pull reads as deliberate and settles OPEN; below it, closed.
                val target = if (pull / maxPull > 0.45f) maxPull else 0f
                latched = target > 0f
                animate(pull, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                    pull = v
                }
                return available
            }
        }
    }

    // Everything the travel drives comes from this one number, so the title cannot end up centred
    // without also being spaced, which is what "it goes to the middle but not diagonally" was.
    val travel = if (maxPull > 0f) (pull / maxPull).coerceIn(0f, 1f) else 0f

    // Released the latch by scrolling? Then close the gap too.
    LaunchedEffect(latched, scrollState.value) {
        if (!latched && scrollState.value > 0 && pull > 0f) {
            animate(pull, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ ->
                pull = v
            }
        }
    }

    // The bar FLOATS over the scroll rather than sitting above it, and the scroll fades out
    // across exactly the height it can reach. Together that is what removes the cut line under
    // the clock: content thins to nothing on its way up instead of stopping at an edge.
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Both edges, not just the top. The page runs under the system navigation bar,
                // which on a gesture or three-button shell is transparent -- so without this the
                // last visible row was cut off by a hard line with the buttons floating on top
                // of it. Softened over the bar's own height plus a little, so the fade is
                // finished before the content the user is actually reading.
                .fadingEdges(top = topInset + barHeight, bottom = bottomInset + 24.dp)
                .then(
                    if (!scrollable) Modifier
                    else Modifier.nestedScroll(if (onRefresh != null) refresh.connection else overscroll)
                )
                .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier),
        ) {
            Spacer(Modifier.height(topInset + barHeight))
            if (onRefresh != null && scrollable) IosRefreshIndicator(refresh)
            if (largeTitle != null) {
                // The title drifts diagonally as the page is pulled: down, and from the leading
                // edge toward the centre. `BiasAlignment.Horizontal` is used rather than a
                // translation because it is direction-aware -- in Persian the title starts on the
                // right and still travels inward, which a raw translationX would get backwards.
                // The vertical half of the diagonal is PADDING, not a translation. Padding is
                // layout, so the account card and everything under it move down with the title
                // instead of the title sliding over them -- and a layout value cannot jump the
                // way a separately-animated offset could.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 18.dp + 30.dp * travel,
                            bottom = 22.dp + 30.dp * travel,
                        )
                ) {
                    Text(
                        largeTitle,
                        color = Ios.Label,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(BiasAlignment(-1f + travel, 0f)),
                    )
                }
            }
            content()
            Spacer(Modifier.height(bottomInset))
        }

        if (hasBar) {
            Box(modifier = Modifier.fillMaxWidth().height(topInset + 44.dp).padding(top = topInset)) {
                if (onBack != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .clickable(onClick = onBack)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // WHITE, not the accent.
                        //
                        // iOS paints this blue, and that is where it came from, but every screen
                        // here sits on a wallpaper rather than on a white sheet: blue type on
                        // glass reads as a stray coloured word floating over a photo, and it was
                        // the first thing the eye landed on at the top of every page. Colour in
                        // this app now means STATE -- a health lamp, a latency band, a
                        // destructive action -- and navigation is not state.
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = Ios.Label,
                            modifier = Modifier
                                .size(22.dp)
                                // Points back the way you came, which is rightward in Persian.
                                .scale(
                                    scaleX = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f,
                                    scaleY = 1f,
                                ),
                        )
                        if (backLabel != null) {
                            Text(backLabel, color = Ios.Label, fontSize = 16.sp)
                        }
                    }
                }
                if (title != null) {
                    // Inset and clipped. A centred title with no width constraint grew straight
                    // through the back control on any page named after user content -- a config
                    // called "کانفیگ ایران ۱ — پیش‌فرض (Google DoH)" overlapped it completely.
                    Text(
                        title,
                        color = Ios.Label,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 88.dp),
                    )
                }
                if (trailing != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(horizontal = 12.dp),
                    ) {
                        trailing()
                    }
                }
            }
        }
    }
}

/** One choice on a picker page. `detail` is the small grey line under the label, when useful. */
data class IosOption(val key: String, val label: String, val detail: String? = null)

/**
 * A single-choice page: rows with a blue tick against the current value. Selecting pops straight
 * back, which is what iOS does and what stops the page needing a Save button.
 */
@Composable
fun IosPickerScreen(
    title: String,
    options: List<IosOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    backLabel: String? = null,
    footer: String? = null,
) {
    IosScreen(title = title, onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            options.forEachIndexed { index, option ->
                if (index > 0) Separator()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelect(option.key)
                            onBack()
                        }
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(option.label, color = Ios.Label, fontSize = 16.sp)
                        if (option.detail != null) {
                            Text(option.detail, color = Ios.SecondaryLabel, fontSize = 12.sp)
                        }
                    }
                    if (option.key == selectedKey) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Ios.Blue,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        if (footer != null) {
            Text(
                footer,
                color = Ios.SecondaryLabel,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A page holding one value the user types: a single field in its own card, with the explanation
 * underneath as a footer the way iOS explains a setting.
 *
 * There is no OK button. `onValueChange` fires on every keystroke and the caller decides whether
 * the value is worth committing yet -- which is the only sane arrangement for a field like a port
 * number, where every prefix of a valid answer is itself a number.
 */
@Composable
fun IosTextScreen(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onBack: () -> Unit,
    backLabel: String? = null,
    footer: String? = null,
    error: String? = null,
    placeholder: String = "",
    numeric: Boolean = false,
) {
    IosScreen(title = title, onBack = onBack, backLabel = backLabel) {
        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = if (error != null) Ios.Destructive else Ios.Label,
                        fontSize = 17.sp,
                    ),
                    cursorBrush = SolidColor(Ios.Blue),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) {
                            androidx.compose.ui.text.input.KeyboardType.Number
                        } else {
                            androidx.compose.ui.text.input.KeyboardType.Text
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(
                                    placeholder,
                                    color = Ios.SecondaryLabel.copy(alpha = 0.6f),
                                    fontSize = 17.sp,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }
        val note = error ?: footer
        if (note != null) {
            Text(
                note,
                color = if (error != null) Ios.Destructive else Ios.SecondaryLabel,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A labelled slider in a card, for the one setting that is a continuous amount. */
@Composable
fun SettingsSlider(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(RowShape)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, color = Ios.Label, fontSize = 16.sp)
            Text(valueLabel, color = Ios.SecondaryLabel, fontSize = 16.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Ios.Blue,
                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
            ),
        )
    }
}
