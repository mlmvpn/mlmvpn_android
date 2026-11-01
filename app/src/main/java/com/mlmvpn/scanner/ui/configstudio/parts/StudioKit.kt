package com.mlmvpn.scanner.ui.configstudio.parts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.mlmvpn.scanner.ui.home.frostedGlass
import com.mlmvpn.scanner.ui.settings.Ios
import com.mlmvpn.scanner.ui.settings.SettingsGroup
import com.mlmvpn.scanner.ui.theme.CardShape
import com.mlmvpn.scanner.ui.theme.PanelShape
import com.mlmvpn.scanner.ui.theme.ControlShape

/**
 * The wizard chrome, extracted once.
 *
 * These pieces were written for `GstSetupWizard` and are `private` there, so Config Studio's wizard
 * would have been the second copy and whatever comes next the third. They are small enough that
 * copying is tempting and exactly the wrong instinct: three copies of a text field drift on padding,
 * on the caret colour, and — the one that actually matters — on whether the field is LTR-pinned.
 *
 * Nothing here knows anything about Config Studio; it is chrome. Anything with product meaning lives
 * in the screen that uses it.
 */

/** Progress through the steps. Wide dash for where you are, dim for where you have been. */
@Composable
fun StepDots(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 1..total) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(6.dp)
                    .width(if (i == current) 20.dp else 6.dp)
                    .background(
                        when {
                            i == current -> Ios.Blue
                            i < current -> Ios.Blue.copy(alpha = 0.45f)
                            else -> Ios.SecondaryLabel.copy(alpha = 0.30f)
                        },
                        RoundedCornerShape(3.dp),
                    )
            )
        }
    }
}

@Composable
fun StepHeader(title: String, subtitle: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        title,
        color = Ios.Label,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        subtitle,
        color = Ios.SecondaryLabel,
        fontSize = 14.sp,
        lineHeight = 23.sp,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(18.dp))
}

/**
 * One typed value, in its own card.
 *
 * `BasicTextField` rather than `OutlinedTextField`: the Material version brings a floating label, an
 * indicator line and its own 56dp metrics, none of which appear anywhere else in this app.
 *
 * **[ltr] defaults to true and that is not a style choice.** This app runs right-to-left, and the
 * things typed into these fields are ASCII — API tokens, usernames that become part of a link, host
 * names. Left to inherit the page direction, bidi throws their punctuation to the wrong end and puts
 * the caret on the wrong side. Set it false only for a field that holds Persian prose, such as a
 * note.
 */
@Composable
fun WizardField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    monospace: Boolean = false,
    ltr: Boolean = true,
    /**
     * How many lines the box stands open at.
     *
     * One, unless what is typed into it genuinely has lines. The bulk-add screen asks for a paste
     * of `email,token` per account and had this at one: ten accounts arrived as a single unbroken
     * run of text scrolling sideways in a 46dp box, with no way to see what had been pasted or
     * where it went wrong.
     */
    lines: Int = 1,
) {
    SettingsGroup {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = if (lines > 1) 46.dp * lines else 46.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = if (lines > 1) Alignment.Top else Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (ltr) LayoutDirection.Ltr else LocalLayoutDirection.current
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = lines <= 1,
                    minLines = lines,
                    textStyle = TextStyle(
                        color = Ios.Label,
                        fontSize = 17.sp,
                        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    ),
                    cursorBrush = SolidColor(Ios.Blue),
                    // The field fills the whole box. A box of several lines is taller than that many
                    // lines of text, and a tap on its lower part used to land outside the field and
                    // do nothing -- the note box on a person's page looked open and was not.
                    modifier = Modifier.fillMaxWidth()
                        .then(if (lines > 1) Modifier.heightIn(min = 46.dp * lines - 16.dp) else Modifier),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
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
    }
}

/**
 * The one full-width action at the foot of a step.
 *
 * [busy] disables it and shows a spinner in place of the label, because the alternative — leaving it
 * tappable during a deploy — produces a second deploy, and on this feature a second deploy is how an
 * account ends up with two workers.
 */
@Composable
fun WizardPrimary(
    label: String,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val active = enabled && !busy
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(if (active) Ios.Blue else Ios.Blue.copy(alpha = 0.35f), ControlShape)
            .then(if (active) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 50.dp)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * An explanatory slab, not attached to a group.
 *
 * **The gap above it is part of the card, not the caller's job.** Forty of the fifty of these in
 * the feature had nothing between them and the group above, so an explanation sat welded to the
 * card it was explaining, while the other ten carried a hand-written spacer of whatever size that
 * screen's author happened to type. Owning the gap is what makes the rhythm the same on every
 * page — the same reason [SettingsSectionHeader] and [SettingsFooter] own theirs.
 *
 * [underHeader] is for the case where this card IS the section rather than a note under one. A
 * section header already leaves 7dp beneath itself, which is where a [SettingsGroup] sits; a card
 * that adds its own 10 on top of that would hang lower than every other card in the app.
 */
@Composable
fun InfoCard(text: String, underHeader: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = if (underHeader) 0.dp else 10.dp)
            .frostedGlass(CardShape)
            .padding(16.dp),
    ) {
        Text(text, color = Ios.SecondaryLabel, fontSize = 13.sp, lineHeight = 22.sp)
    }
}

/** The same slab, in a colour that means something went wrong. */
@Composable
fun ProblemCard(text: String, underHeader: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = if (underHeader) 0.dp else 10.dp)
            .background(Ios.Red.copy(alpha = 0.12f), CardShape)
            .padding(16.dp),
    ) {
        Text(text, color = Ios.Label, fontSize = 13.sp, lineHeight = 22.sp)
    }
}

/**
 * An ASCII payload -- a link, a token, a UUID -- rendered so it can be read AND copied.
 *
 * Always LTR-pinned. An RTL container reorders these both on screen and in what the person copies,
 * so a link that looks right in a screenshot pastes broken. In a feature whose product is handing
 * someone a working link, that is not cosmetic (plan R9).
 */
@Composable
fun PayloadText(value: String, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            value,
            modifier = modifier,
            color = Ios.Label,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Start,
        )
    }
}

/** Centered progress with a line of text, for a step that is waiting on the network. */
@Composable
fun ColumnScope.WizardBusy(message: String) {
    Spacer(Modifier.height(28.dp))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Ios.Blue, strokeWidth = 3.dp)
    }
    Spacer(Modifier.height(14.dp))
    Text(
        message,
        color = Ios.SecondaryLabel,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
    )
    Spacer(Modifier.height(28.dp))
}

/**
 * A thin fill bar for a fraction between 0 and 1.
 *
 * Drawn only when there is something to be a fraction **of**: the caller passes null when the number
 * behind it has not been read yet, and gets nothing rather than an empty bar. An empty bar and a bar
 * with no data behind it look identical, and only one of them means "there is room here".
 */
@Composable
fun MeterBar(fraction: Float?, tint: Color, modifier: Modifier = Modifier) {
    if (fraction == null) return
    Box(
        modifier = modifier.fillMaxWidth().height(4.dp)
            .background(Ios.Separator, RoundedCornerShape(2.dp))
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp)
                .background(tint, RoundedCornerShape(2.dp))
        )
    }
}

/**
 * A card whose content is a short block of stated facts rather than rows.
 *
 * It exists because these screens reached for [SettingsFooter] inside a [SettingsGroup] to do this,
 * nine times over, and a footer is not a row. It is the grey paragraph iOS puts UNDER a card: 32dp
 * side insets to clear the card's own, 8dp above and NOTHING below. Placed inside a group it drew a
 * card whose text was indented twice as far as the rows above it and pressed flat against the
 * bottom edge — which is most of why these pages did not read as part of the app.
 *
 * White at 15sp rather than grey at 13. What goes in here is the figure the operator opened the
 * screen to read; grey in this app means a caption ABOUT content, not the content.
 */
@Composable
fun FactsCard(text: String) {
    SettingsGroup {
        Text(
            text,
            color = Ios.Label,
            fontSize = 15.sp,
            lineHeight = 25.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        )
    }
}

/**
 * An ASCII payload on a surface of its own.
 *
 * [PayloadText] by itself is bare 12sp monospace, and every screen here laid it straight onto the
 * wallpaper between two glass cards — the one piece of content in the feature with nothing behind
 * it. It is also the piece most likely to be read back over a phone call or photographed, which is
 * exactly when a legible ground earns its keep.
 */
@Composable
fun PayloadCard(value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .frostedGlass(CardShape)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        PayloadText(value, Modifier.fillMaxWidth())
    }
}

/**
 * A filter chip.
 *
 * One of these, not three. The user list and the analytics ranges each grew their own, at 14dp and
 * 10dp, against an [Ios.Card] fill — and both painted the SELECTED label `Ios.Card` as well, a
 * near-black on a blue ground that is not readable at any size. [ControlShape] is the app's radius
 * for a thing you press, glass is what an unfilled control sits on everywhere else, and the label
 * is white in both states because the fill is what says which one is chosen.
 */
@Composable
fun StudioChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = Ios.Label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = modifier
            .then(
                if (selected) Modifier.background(Ios.Blue, ControlShape)
                else Modifier.frostedGlass(ControlShape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * The frame for one item in a list this feature scrolls itself.
 *
 * Glass, like every other card in the app. Both list screens were filling theirs with [Ios.Card] —
 * the OPAQUE #1C1C1E the palette keeps only as the no-wallpaper fallback — so a row of users sat as
 * a flat black slab among translucent cards above and below it. These were the only two places in
 * the app that drew a card that way.
 */
@Composable
fun StudioListRow(onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .frostedGlass(CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** The health lamp on the leading edge of a list row. One size, so two lists cannot disagree. */
@Composable
fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(9.dp).background(color, CircleShape))
}

/**
 * The lead-in line on a page that already carries its name in the navigation bar.
 *
 * [StepHeader] is wizard chrome: 22sp bold, because a wizard step has no bar title to repeat. Three
 * pushed pages used it anyway and printed their own name a second time, in larger type, directly
 * under the bar that was already showing it -- «کاربر جدید» over «کاربر جدید». What those pages
 * actually wanted was the second line, which is what this draws.
 */
@Composable
fun PageIntro(text: String) {
    Text(
        text,
        color = Ios.SecondaryLabel,
        fontSize = 14.sp,
        lineHeight = 23.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
    )
}

/**
 * One shortcut, as a tile.
 *
 * The glyph sits in a tinted disc rather than being coloured on its own, which is what lets nine of
 * these read as one grid instead of nine loose icons: the disc is a constant shape at a constant
 * size, so the eye lands on the layout before it lands on the colours. The label is two lines at
 * most and never ellipsised mid-word — a tile whose name is cut is a tile nobody presses.
 *
 * Fixed height rather than intrinsic, because a grid whose rows are as tall as their longest label
 * is a grid with three different row heights, and that reads as a mistake rather than as a shape.
 */
@Composable
fun StudioTile(
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The whole tile dips, the way an iOS home icon does. A ripple would be Material's answer and
    // would look borrowed next to everything else on this screen.
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "tilePress")

    Column(
        modifier = modifier
            .scale(scale)
            .height(96.dp)
            .clip(PanelShape)
            .frostedGlass(PanelShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(9.dp))
        Text(
            label,
            color = Ios.Label,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One row of the grid, padded and spaced like every other card on the screen.
 *
 * Every row is full, which is why this can be a plain `Row` of equally weighted children: nine
 * shortcuts in three rows of three. A grid whose last row is short would need the tiles to keep
 * their width rather than stretch, and the shape chosen here avoids that question rather than
 * answering it — three rows of three is also what makes the block scan as a keypad.
 */
@Composable
fun StudioTileRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
