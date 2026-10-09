// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.cassini.ui.theme.Tone
import org.cassini.ui.theme.colors

/**
 * A 32 dp trust chip on Read & listen (radius 16, padding 12, gap 6, 13 sp 600): "Verified" is [Tone.Ok] with
 * Check 14/2.2; the other trust states use [Tone.Warn] or [Tone.Error] with the Warn glyph (14/2). Not interactive.
 */
@Composable
fun LargeChip(text: String, tone: Tone, modifier: Modifier = Modifier, glyph: Glyph = defaultGlyph(tone)) {
    val colors = tone.colors
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(CassiniTheme.shapes.card)
            .background(colors.bg)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CassiniIcon(glyph, 14.dp, if (glyph == Glyph.Check) 2.2f else 2f, colors.fg, null)
        Text(text = text, style = CassiniTheme.type.speaker, color = colors.fg, maxLines = 1)
    }
}

private fun defaultGlyph(tone: Tone): Glyph = if (tone == Tone.Ok) Glyph.Check else Glyph.Warn

/**
 * The version chip (visual 32 / layout 48): surface, 1 dp line2, padding 12/10, 13 sp ink and ChevronDown 14/2.
 * Opens the Versions sheet.
 */
@Composable
fun VersionChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlineChip(text = text, onClick = onClick, modifier = modifier, glyph = Glyph.ChevronDown, leading = false)
}

/**
 * "Follow playback" (no board): the [VersionChip] style with Play 14 before the text. The Note screen floats it
 * 16 dp above the player, centred, while auto-follow is paused.
 */
@Composable
fun FollowChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlineChip(text = text, onClick = onClick, modifier = modifier, glyph = Glyph.Play, leading = true)
}

@Composable
private fun OutlineChip(text: String, onClick: () -> Unit, modifier: Modifier, glyph: Glyph, leading: Boolean) {
    val c = CassiniTheme.colors
    val shape = CassiniTheme.shapes.card
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .touchTarget()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 32.dp)
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.line2, shape)
                .indication(interaction, ripple())
                .padding(start = if (leading) 10.dp else 12.dp, end = if (leading) 12.dp else 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val stroke = if (glyph.filled) 0f else 2f
            if (leading) CassiniIcon(glyph, 14.dp, stroke, c.ink, null)
            Text(text = text, style = CassiniTheme.type.caption, color = c.ink, maxLines = 1)
            if (!leading) CassiniIcon(glyph, 14.dp, stroke, c.ink, null)
        }
    }
}

@Preview
@Composable
internal fun LargeChipPreview() {
    CassiniTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LargeChip("Verified", Tone.Ok)
            VersionChip("Version 2 of 2 · speakers merged", onClick = {})
        }
    }
}
