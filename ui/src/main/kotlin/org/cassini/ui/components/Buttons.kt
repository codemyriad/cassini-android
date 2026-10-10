// Composables are nouns in PascalCase; the numbers are the button table of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniColors
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/** The busy spinner of every button: 16 dp, drawn over the hidden label so the width is kept. */
private val SpinnerSize = 16.dp
private val SpinnerStroke = 2.dp

/** `PrimaryButton` sizes: [Large] is the 56 dp full-width call to action, [Regular] the 48 dp button. */
enum class PrimaryButtonSize { Large, Regular }

/** `InkButton` sizes: visual 36 or 40, both laid out at 48. */
enum class InkButtonSize(val visual: Dp) { Small(36.dp), Medium(40.dp) }

/**
 * `SecondaryButton` sizes (visual / layout, padding, fill, text):
 * [Large] 52/52, 20, surface, `button` ("Import audio", with an icon);
 * [Regular] 48/48, 18, transparent, `buttonRegular` ("Cancel");
 * [Block] 44/48, full width, surface, `label14` ("Stop and keep progress");
 * [Medium] 40/48, 16, surface, `label14` ("Restore");
 * [MediumPlain] 40/48, 14, surface, `body14Plain` ("Someone else");
 * [Small] 36/48, 14, surface, `body14Plain` (banner and suggestion buttons).
 */
enum class SecondaryButtonSize(val visual: Dp, val padding: Dp) {
    Large(52.dp, 20.dp),
    Regular(48.dp, 18.dp),
    Block(44.dp, 0.dp),
    Medium(40.dp, 16.dp),
    MediumPlain(40.dp, 14.dp),
    Small(36.dp, 14.dp),
}

/** `TextButton` heights: 48 (Merge into…) or 52 (Not now — keep the audio). */
enum class TextButtonSize(val visual: Dp) { Regular(48.dp), Tall(52.dp) }

/** `DestructiveTextButton` sizes: [Header] 44/48, 14, `button` (Trash "Empty"); [Small] 40/48, 12, `body14Plain` ("Delete now"). */
enum class DestructiveTextButtonSize(val visual: Dp, val padding: Dp) { Header(44.dp, 14.dp), Small(40.dp, 12.dp) }

/**
 * The pill every button of the table draws. The clickable node is laid out at 48 dp at least ([touchTarget]); the
 * board visual of [visual] dp is centred inside and carries the clipped ripple in [textColor]. Disabled draws at
 * 38 % alpha; [busy] hides the label behind a 16 dp spinner and keeps the width. [fillWidth] stretches the visual to the
 * full width.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // One shared body for the whole table.
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    visual: Dp,
    padding: Dp,
    fill: Color,
    border: Color?,
    textStyle: TextStyle,
    textColor: Color,
    enabled: Boolean = true,
    busy: Boolean = false,
    fillWidth: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    stateDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = CassiniTheme.shapes.pill
    Box(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .touchTarget()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !busy,
                role = Role.Button,
                onClick = onClick,
            )
            .then(
                if (stateDescription != null) {
                    Modifier.semantics { this.stateDescription = stateDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = visual)
                .clip(shape)
                .background(fill)
                .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), shape) else Modifier)
                .indication(interaction, ripple(color = textColor))
                .padding(horizontal = padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier.alpha(if (busy) 0f else 1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leading?.invoke()
                    Text(text = text, style = textStyle, color = textColor, textAlign = TextAlign.Center)
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(SpinnerSize),
                        color = textColor,
                        strokeWidth = SpinnerStroke,
                    )
                }
            }
        }
    }
}

/**
 * The accent button. [PrimaryButtonSize.Large] fills the width (56 dp, `cta`); [PrimaryButtonSize.Regular] is 48 dp
 * with 22 dp padding. When [blockedReason] is set the button keeps its enabled look, a press calls [onBlocked]
 * instead of [onClick], and its state description is the reason (the Name sheet's Save, features.md section 4).
 */
@Composable
@Suppress("LongParameterList") // ui.md section 6: size, enabled, busy and the blocked state.
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: PrimaryButtonSize = PrimaryButtonSize.Regular,
    enabled: Boolean = true,
    busy: Boolean = false,
    blockedReason: String? = null,
    onBlocked: () -> Unit = {},
) {
    val c = CassiniTheme.colors
    val large = size == PrimaryButtonSize.Large
    PillButton(
        text = text,
        onClick = if (blockedReason != null) onBlocked else onClick,
        modifier = modifier,
        visual = if (large) 56.dp else 48.dp,
        padding = if (large) 16.dp else 22.dp,
        fill = c.accentFill,
        border = null,
        textStyle = if (large) CassiniTheme.type.cta else CassiniTheme.type.button,
        textColor = c.onAccentFill,
        enabled = enabled,
        busy = busy,
        fillWidth = large,
        stateDescription = blockedReason,
    )
}

/** The ink pill: the first banner action, "Same person", "Yes, Luca" (`label14` on inkFill). */
@Composable
@Suppress("LongParameterList") // Size, padding override, enabled and busy.
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: InkButtonSize = InkButtonSize.Small,
    padding: Dp = 14.dp,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val c = CassiniTheme.colors
    PillButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        visual = size.visual,
        padding = padding,
        fill = c.inkFill,
        border = null,
        textStyle = CassiniTheme.type.label14,
        textColor = c.onInkFill,
        enabled = enabled,
        busy = busy,
    )
}

/** The outlined pill in the sizes of [SecondaryButtonSize], with an optional leading [glyph] (20 dp, stroke 1.8). */
@Composable
@Suppress("LongParameterList") // Size, icon, padding override, enabled and busy.
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: SecondaryButtonSize = SecondaryButtonSize.Small,
    glyph: Glyph? = null,
    padding: Dp = size.padding,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    val style = when (size) {
        SecondaryButtonSize.Large -> t.button
        SecondaryButtonSize.Regular -> t.buttonRegular
        SecondaryButtonSize.Block, SecondaryButtonSize.Medium -> t.label14
        SecondaryButtonSize.MediumPlain, SecondaryButtonSize.Small -> t.body14Plain
    }
    PillButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        visual = size.visual,
        padding = padding,
        fill = if (size == SecondaryButtonSize.Regular) Color.Transparent else c.surface,
        border = c.line2,
        textStyle = style,
        textColor = c.ink,
        enabled = enabled,
        busy = busy,
        fillWidth = size == SecondaryButtonSize.Block,
        leading = glyph?.let { { CassiniIcon(it, 20.dp, 1.8f, c.ink, null) } },
    )
}

/** The blue text button (`button` in accentInk, padding 14): "Merge into…", "Not now — keep the audio". */
@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: TextButtonSize = TextButtonSize.Regular,
    enabled: Boolean = true,
) {
    PillButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        visual = size.visual,
        padding = 14.dp,
        fill = Color.Transparent,
        border = null,
        textStyle = CassiniTheme.type.button,
        textColor = CassiniTheme.colors.accentInk,
        enabled = enabled,
    )
}

/** The red text button: Trash "Empty" ([DestructiveTextButtonSize.Header]) and "Delete now" ([DestructiveTextButtonSize.Small]). */
@Composable
fun DestructiveTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: DestructiveTextButtonSize = DestructiveTextButtonSize.Small,
    enabled: Boolean = true,
) {
    val t = CassiniTheme.type
    PillButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        visual = size.visual,
        padding = size.padding,
        fill = Color.Transparent,
        border = null,
        textStyle = if (size == DestructiveTextButtonSize.Header) t.button else t.body14Plain,
        textColor = CassiniTheme.colors.redInk,
        enabled = enabled,
    )
}

/** The 52 dp full-width red outline button ("Forget everyone…"); the screen gives the 16 dp margins. */
@Composable
fun DestructiveOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val c = CassiniTheme.colors
    PillButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        visual = 52.dp,
        padding = 16.dp,
        fill = c.surface,
        border = c.redLine,
        textStyle = CassiniTheme.type.button,
        textColor = c.redInk,
        enabled = enabled,
        busy = busy,
        fillWidth = true,
    )
}

/**
 * The destructive dialog confirm: 48 dp, padding 22, redInk fill. The label is white in light; in dark redInk is a
 * light colour, so the label is [CassiniColors.bg], as the Material `onError` mapping.
 */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val c = CassiniTheme.colors
    PillButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        visual = 48.dp,
        padding = 22.dp,
        fill = c.redInk,
        border = null,
        textStyle = CassiniTheme.type.button,
        textColor = c.onDestructive(),
        enabled = enabled,
        busy = busy,
    )
}

private fun CassiniColors.onDestructive(): Color = if (isDark) bg else onRed

@Preview
@Composable
internal fun ButtonsPreview() {
    CassiniTheme {
        Column(
            modifier = Modifier
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton("Download and transcribe", onClick = {}, size = PrimaryButtonSize.Large)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", onClick = {}, size = SecondaryButtonSize.Regular)
                PrimaryButton("Save name", onClick = {})
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton("Resume", onClick = {})
                SecondaryButton("Start over", onClick = {})
                DestructiveTextButton("Delete now", onClick = {})
            }
            SecondaryButton("Import audio", onClick = {}, size = SecondaryButtonSize.Large, glyph = Glyph.Import)
            TextButton("Not now — keep the audio", onClick = {}, size = TextButtonSize.Tall)
            DestructiveOutlineButton("Forget everyone…", onClick = {})
            DestructiveButton("Delete", onClick = {})
        }
    }
}
