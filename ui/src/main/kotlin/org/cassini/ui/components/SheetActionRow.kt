// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6. The file
// is named after its main composable, not after the SheetRowStyle enum it also holds.
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cassini.ui.R
import org.cassini.ui.format.LocalFormat
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.cassini.ui.theme.PlexMono

/**
 * The three kinds of sheet row (board 06): [Featured] is the highlighted "Cassini document" row, [Plain] an ordinary
 * action, [Destructive] "Move to trash" (redInk label and icon, the subtitle stays text2).
 */
enum class SheetRowStyle { Plain, Featured, Destructive }

/** The Share button at the end of a "Save a copy" row: its spoken [label] ("Share Cassini document") and [onClick]. */
data class RowShare(val label: String, val onClick: () -> Unit)

/**
 * An action row of a sheet. [SheetRowStyle.Featured]: surfaceWash, radius 14, padding 12, gap 14, top-aligned; the
 * [glyph] (24/1.7) in accent; [label] in `rowTitle`; [subtitle] in `caption` (line height 1.4 in a 19.5 dp line box) followed by the mono
 * [extension] (".opus"). [SheetRowStyle.Plain] and [SheetRowStyle.Destructive]: min height 52, padding 0/12, gap 14,
 * centred; the glyph in the label colour; [label] in `body16` followed by the mono [extension] (13 sp, text2); an
 * optional [subtitle] below in `caption` text2. A disabled row draws at 38 % and its [subtitle] says why. A [busy] row
 * (an export being written) shows a 20 dp spinner at the end, does not respond to taps and reports "Saving…" as its
 * state. A [share] row ends with a 44 dp Share button of its own (an accessibility node of its own), disabled with the
 * row.
 */
@Composable
@Suppress("LongParameterList") // ui.md section 6: icon, label, extension, subtitle, style and states.
fun SheetActionRow(
    label: String,
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    extension: String? = null,
    subtitle: String? = null,
    style: SheetRowStyle = SheetRowStyle.Plain,
    enabled: Boolean = true,
    busy: Boolean = false,
    share: RowShare? = null,
) {
    val c = CassiniTheme.colors
    val featured = style == SheetRowStyle.Featured
    val saving = stringResource(R.string.ui_actions_saving)
    val shape = CassiniTheme.shapes.row
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .then(if (featured) Modifier.background(c.surfaceWash) else Modifier)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .then(
                if (busy) Modifier.semantics { stateDescription = saving } else Modifier,
            )
            .then(
                if (featured) {
                    Modifier.padding(12.dp)
                } else {
                    Modifier
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 12.dp)
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = if (featured) Alignment.Top else Alignment.CenterVertically,
    ) {
        val tint = when (style) {
            SheetRowStyle.Featured -> c.accent
            SheetRowStyle.Plain -> c.ink
            SheetRowStyle.Destructive -> c.redInk
        }
        CassiniIcon(glyph, 24.dp, 1.7f, tint, null)
        RowTexts(label, extension, subtitle, featured, tint, Modifier.weight(1f))
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(20.dp),
                color = c.accent,
                strokeWidth = 2.dp,
            )
        }
        if (share != null) {
            IconButton44(
                glyph = Glyph.Share,
                contentDescription = share.label,
                onClick = share.onClick,
                modifier = Modifier.align(Alignment.CenterVertically),
                tint = tint,
                enabled = enabled && !busy,
            )
        }
    }
}

/** The label (and the featured row's caption) of a [SheetActionRow], with the mono [extension]. */
@Composable
@Suppress("LongParameterList") // The row's texts, its style and the label colour.
private fun RowTexts(
    label: String,
    extension: String?,
    subtitle: String?,
    featured: Boolean,
    tint: Color,
    modifier: Modifier,
) {
    val c = CassiniTheme.colors
    Column(modifier) {
        if (featured) {
            Text(text = label, style = CassiniTheme.type.rowTitle, color = c.ink)
            if (subtitle != null || extension != null) {
                Text(
                    text = withExtension(subtitle, extension, 13),
                    // Board 06 sets the 13 px lines (lh 1.4) inline in the 16 px row title's font, so each line box
                    // is the 16 px strut's 19.5 dp, not 18.2. Greedy breaking, as the browser does.
                    style = CassiniTheme.type.caption.copy(lineHeight = 19.5.sp, lineBreak = LineBreak.Simple),
                    color = c.text2,
                )
            }
        } else {
            Text(
                text = withExtension(label, extension, 13, c.text2),
                style = CassiniTheme.type.body16,
                color = tint,
            )
            if (subtitle != null) {
                Text(text = subtitle, style = CassiniTheme.type.caption, color = c.text2)
            }
        }
    }
}

/** [text] followed by a space, a break opportunity and [extension] in Plex Mono of [size] sp (in [color] when given). */
private fun withExtension(
    text: String?,
    extension: String?,
    size: Int,
    color: Color = Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    if (text != null) append(text)
    if (extension != null) {
        // A zero-width space lets the line break before ".opus": Unicode line breaking never breaks before a full
        // stop, even after a space, and would move the last word of the text with it (board 06 breaks there).
        if (text != null) append(" \u200B")
        withStyle(SpanStyle(fontFamily = PlexMono, fontSize = size.sp, color = color)) { append(extension) }
    }
}

/** A sheet section header: `section` (uppercase) in text2, margins 8 top, 12 horizontal, 4 bottom; a heading. */
@Composable
fun SheetSection(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(LocalFormat.current.locale),
        modifier = modifier
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
            .semantics { heading() },
        style = CassiniTheme.type.section,
        color = CassiniTheme.colors.text2,
    )
}

/** The 1 dp line between sheet groups, with margins 8 vertical and 12 horizontal. */
@Composable
fun SheetDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(vertical = 8.dp, horizontal = 12.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(CassiniTheme.colors.line),
    )
}

@Preview
@Composable
internal fun SheetActionRowPreview() {
    CassiniTheme {
        Column(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.surface)
                .padding(horizontal = 12.dp),
        ) {
            SheetSection("Save a copy")
            SheetActionRow(
                label = "Cassini document",
                glyph = Glyph.Music,
                onClick = {},
                extension = ".opus",
                subtitle = "Audio with the transcript inside. Plays anywhere.",
                style = SheetRowStyle.Featured,
            )
            SheetActionRow("Transcript as text", Glyph.TextFile, onClick = {}, extension = ".txt")
            SheetDivider()
            SheetActionRow(
                label = "Move to trash",
                glyph = Glyph.Trash,
                onClick = {},
                subtitle = "Kept for 30 days, then deleted.",
                style = SheetRowStyle.Destructive,
            )
        }
    }
}
