// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.LocalFormat
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.cassini.ui.theme.Tone
import org.cassini.ui.theme.colors

/**
 * A settings or sheet section header: `section` in text2, uppercase, padding 20 horizontal, top 22 (16 when
 * [first]), bottom 6; a heading.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, first: Boolean = false) {
    Text(
        text = text.uppercase(LocalFormat.current.locale),
        modifier = modifier
            .padding(start = 20.dp, end = 20.dp, top = if (first) 16.dp else 22.dp, bottom = 6.dp)
            .semantics { heading() },
        style = CassiniTheme.type.section,
        color = CassiniTheme.colors.text2,
    )
}

/**
 * A settings group: surface, radius 16 (Saved voices passes the 18 dp [shape]), an optional 1 dp line border
 * ([bordered]; it sits outside the rows, as the CSS border) and a 1 dp line divider between direct children.
 * The screen gives the 16 dp horizontal margins.
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    bordered: Boolean = false,
    shape: Shape = CassiniTheme.shapes.card,
    content: @Composable () -> Unit,
) {
    val c = CassiniTheme.colors
    var dividers by remember { mutableStateOf(emptyList<Int>()) }
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .then(if (bordered) Modifier.border(1.dp, c.line, shape).padding(1.dp) else Modifier)
            .drawWithContent {
                drawContent()
                val hairline = 1.dp.toPx()
                for (y in dividers) {
                    drawRect(c.line, topLeft = Offset(0f, y.toFloat()), size = Size(size.width, hairline))
                }
            },
    ) { measurables, constraints ->
        val hairline = 1.dp.roundToPx()
        val childConstraints = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }
        val gaps = (placeables.size - 1).coerceAtLeast(0)
        val content = placeables.sumOf { it.height } + gaps * hairline
        val height = content.coerceIn(constraints.minHeight, constraints.maxHeight)
        val lines = ArrayList<Int>(gaps)
        var y = 0
        placeables.forEachIndexed { index, placeable ->
            if (index > 0) {
                lines += y
                y += hairline
            }
            y += placeable.height
        }
        // Only the draw phase reads the offsets: the write must not make this measure block observe them, or every
        // change would measure the card a second time.
        Snapshot.withoutReadObservation { if (lines != dividers) dividers = lines }
        layout(constraints.maxWidth, height) {
            var top = 0
            placeables.forEachIndexed { index, placeable ->
                if (index > 0) top += hairline
                placeable.place(0, top)
                top += placeable.height
            }
        }
    }
}

/** What ends a [SettingsRow]: nothing, a ChevronRight 18/1.8, or a status text (13 sp 600) in a tone's text colour. */
sealed interface SettingsTrailing {
    data object None : SettingsTrailing

    data object Chevron : SettingsTrailing

    data class Status(val text: String, val tone: Tone) : SettingsTrailing
}

/**
 * A row in a [GroupCard]: padding 14/16, gap 12; [title] (`button`, ink) over [subtitle] (`caption`, text2), then
 * the [trailing] part. Clickable (a button) when [onClick] is set. Clickable or not, the row is one accessibility
 * node, so a status is read with the thing it describes ("Speech recognition, INT8 · 640 MB, Ready").
 *
 * As the board's flex row, the trailing part keeps its natural width while the title column can still fit its
 * longest word; beyond that the trailing text wraps (end-aligned) instead of breaking the title inside a word.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: SettingsTrailing = SettingsTrailing.None,
    onClick: (() -> Unit)? = null,
) {
    val c = CassiniTheme.colors
    Layout(
        content = {
            Column {
                Text(text = title, style = CassiniTheme.type.button, color = c.ink)
                if (subtitle != null) Text(text = subtitle, style = CassiniTheme.type.caption, color = c.text2)
            }
            when (trailing) {
                SettingsTrailing.None -> Unit
                SettingsTrailing.Chevron -> CassiniIcon(Glyph.ChevronRight, 18.dp, 1.8f, c.ink, null)
                is SettingsTrailing.Status -> Text(
                    text = trailing.text,
                    style = CassiniTheme.type.speaker,
                    color = trailing.tone.colors.fg,
                    textAlign = TextAlign.End,
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val titleMeasurable = measurables[0]
        val end = measurables.getOrNull(1)?.let { trailingMeasurable ->
            val titleMin = titleMeasurable.minIntrinsicWidth(constraints.maxHeight)
            val maxEnd = (width - gap - titleMin).coerceAtLeast(width / 3)
            trailingMeasurable.measure(Constraints(maxWidth = maxEnd.coerceAtLeast(0)))
        }
        val titleWidth = (width - (end?.let { it.width + gap } ?: 0)).coerceAtLeast(0)
        val titlePlaceable = titleMeasurable.measure(Constraints(minWidth = titleWidth, maxWidth = titleWidth))
        val height = maxOf(titlePlaceable.height, end?.height ?: 0).coerceAtLeast(constraints.minHeight)
        layout(width, height) {
            titlePlaceable.place(0, (height - titlePlaceable.height) / 2)
            end?.place(width - end.width, (height - end.height) / 2)
        }
    }
}

/** The helper line under a settings group: `caption` in text2, line height 1.4, margin top 6, horizontal 20. */
@Composable
fun HelperText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
        style = CassiniTheme.type.caption.copy(lineHeight = CassiniTheme.type.caption.fontSize * 1.4f),
        color = CassiniTheme.colors.text2,
    )
}

/**
 * The About card (board 08): surface, radius 16, padding 14/16, `about` in text3. "Cassini [version]" in bold ink,
 * the tagline, then "Free software under GPLv3." and the "Source code and notices" link, a [LinkAnnotation.Clickable]
 * that calls [onSourceClick].
 */
@Composable
fun AboutCard(version: String, onSourceClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val brand = stringResource(R.string.ui_brand_version, version)
    val tagline = stringResource(R.string.ui_settings_about_body)
    val free = stringResource(R.string.ui_settings_free_software)
    val source = stringResource(R.string.ui_settings_source_and_notices)
    val linkStyle = TextLinkStyles(style = SpanStyle(color = c.link, textDecoration = TextDecoration.Underline))
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CassiniTheme.shapes.card)
            .background(c.surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.W700, color = c.ink)) { append(brand) }
                append(' ')
                append(tagline)
            },
            style = CassiniTheme.type.about,
            color = c.text3,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildAnnotatedString {
                append(free)
                append(' ')
                withLink(LinkAnnotation.Clickable(tag = "source", styles = linkStyle) { onSourceClick() }) {
                    append(source)
                }
            },
            style = CassiniTheme.type.about,
            color = c.text3,
        )
    }
}

@Preview
@Composable
internal fun SettingsPreview() {
    CassiniTheme {
        Column(
            Modifier
                .background(CassiniTheme.colors.bg)
                .padding(bottom = 16.dp),
        ) {
            SectionHeader("On-device models", first = true)
            GroupCard(Modifier.padding(horizontal = 16.dp)) {
                SettingsRow(
                    "Speech recognition",
                    subtitle = "INT8 · 640 MB",
                    trailing = SettingsTrailing.Status("Ready", Tone.Ok),
                )
                SettingsRow("Saved voices", subtitle = "4 people", trailing = SettingsTrailing.Chevron, onClick = {})
            }
            HelperText("Transcription runs on this phone. Audio never leaves it.")
            SectionHeader("About")
            AboutCard(version = "0.1.0", onSourceClick = {}, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
