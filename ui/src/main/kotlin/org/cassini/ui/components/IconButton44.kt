// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/** The visual diameter of the board's icon buttons. */
internal val IconButtonVisual: Dp = 44.dp

/** Alpha of a disabled control (docs/design/ui.md section 6, buttons). */
internal const val DISABLED_ALPHA = 0.38f

/**
 * The board's 44 dp round icon button, laid out at 48 dp (visual 44 / layout 48): a [glyph] of [iconSize] dp at
 * [stroke] in [tint] (ink by default), a circular ripple of [visual] dp and a required [contentDescription].
 */
@Composable
@Suppress("LongParameterList") // The icon's own size, stroke and tint are part of each board use.
fun IconButton44(
    glyph: Glyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    stroke: Float = 1.8f,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    visual: Dp = IconButtonVisual,
) {
    val interaction = remember { MutableInteractionSource() }
    val color = if (tint == Color.Unspecified) CassiniTheme.colors.ink else tint
    Box(
        modifier = modifier
            .touchTarget()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(visual)
                .clip(CircleShape)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            CassiniIcon(glyph, iconSize, stroke, color, contentDescription)
        }
    }
}

@Preview
@Composable
internal fun IconButton44Preview() {
    CassiniTheme { IconButton44(Glyph.Back, contentDescription = "Back", onClick = {}) }
}
