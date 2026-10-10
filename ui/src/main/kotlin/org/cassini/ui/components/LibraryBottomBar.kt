// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.Durations
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.cassini.ui.theme.RecordShadow

/**
 * The Record button (board 00): 64 dp high, radius 32, padding 22/26, red with the red drop shadow, gap 10, an
 * 18 dp white circle and "Record" (`fab`, white); described "Record a new note". While a recording runs
 * ([recordingElapsedMs] set) it reads "Recording · 24:07" with a live dot inside the circle and is described
 * "Return to recording".
 */
@Composable
fun RecordFab(onClick: () -> Unit, modifier: Modifier = Modifier, recordingElapsedMs: Long? = null) {
    val c = CassiniTheme.colors
    val shape = CassiniTheme.shapes.pill
    val label = recordLabel(recordingElapsedMs)
    val description = stringResource(
        if (recordingElapsedMs == null) R.string.ui_library_record_description else R.string.ui_library_return_to_recording,
    )
    Row(
        modifier = modifier
            .heightIn(min = 64.dp)
            .dropShadow(
                shape,
                Shadow(
                    radius = RecordShadow.radius,
                    color = c.red,
                    offset = DpOffset(0.dp, RecordShadow.offsetY),
                    alpha = RecordShadow.ALPHA,
                ),
            )
            .clip(shape)
            .background(c.red)
            .clickable(
                interactionSource = null,
                indication = ripple(color = c.onRed),
                role = Role.Button,
                onClick = onClick,
            )
            .clearAndSetSemantics { contentDescription = description }
            .padding(start = 22.dp, end = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FabDot(live = recordingElapsedMs != null)
        Text(
            text = label,
            style = CassiniTheme.type.fab,
            color = c.onRed,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Record", or "Recording · 24:07" while a recording runs. */
@Composable
private fun recordLabel(recordingElapsedMs: Long?): String =
    if (recordingElapsedMs == null) {
        stringResource(R.string.ui_library_record)
    } else {
        stringResource(R.string.ui_library_recording_fab, Durations.clock(recordingElapsedMs))
    }

/** The Record button's 18 dp white circle; while a recording runs ([live]) it holds an 8 dp red dot. */
@Composable
private fun FabDot(live: Boolean) {
    val c = CassiniTheme.colors
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(c.onRed),
        contentAlignment = Alignment.Center,
    ) {
        if (live) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(c.red),
            )
        }
    }
}

/** Import while recording: a 52 dp surface circle with a 1 dp line2 border and Import 20/1.8 in ink. */
@Composable
private fun ImportIconButton(description: String, onClick: () -> Unit) {
    val c = CassiniTheme.colors
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(c.surface)
            .border(1.dp, c.line2, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CassiniIcon(Glyph.Import, 20.dp, 1.8f, c.ink, description)
    }
}

/**
 * The Library bottom bar: Import (left) and [RecordFab] (right) over a gradient from transparent bg to bg at 40 %;
 * padding 16 horizontal, top 40, bottom 24 plus the navigation bar inset. The list gives itself a bottom content
 * padding of 128 dp plus the navigation bars.
 *
 * When "Import audio" and the Record button do not both fit at their full width (while a recording runs, no board,
 * "Recording · 24:07" never fits at 390 dp; at large font scales "Record" does not either), Import becomes a 52 dp
 * round icon button with the same description. The Record label can then wrap to a second line; it ends with an
 * ellipsis only if even that runs out.
 */
@Composable
fun LibraryBottomBar(
    onImport: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
    recordingElapsedMs: Long? = null,
) {
    val bg = CassiniTheme.colors.bg
    val t = CassiniTheme.type
    val importLabel = stringResource(R.string.ui_library_import)
    val recordLabel = recordLabel(recordingElapsedMs)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to bg.copy(alpha = 0f), 0.4f to bg))
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 24.dp),
    ) {
        val density = LocalDensity.current
        val recording = recordingElapsedMs != null
        val fullImport = remember(recording, importLabel, recordLabel, t, density, maxWidth) {
            if (recording) {
                false
            } else {
                val importText = measurer.measure(importLabel, t.button, maxLines = 1).size.width
                val recordText = measurer.measure(recordLabel, t.fab, maxLines = 1).size.width
                with(density) { (importText + recordText).toDp() } + ImportChrome + BarGap + RecordChrome <= maxWidth
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fullImport) {
                SecondaryButton(
                    text = importLabel,
                    onClick = onImport,
                    size = SecondaryButtonSize.Large,
                    glyph = Glyph.Import,
                )
            } else {
                ImportIconButton(importLabel, onImport)
            }
            Spacer(Modifier.width(BarGap))
            RecordFab(
                onClick = onRecord,
                modifier = Modifier.weight(1f, fill = false),
                recordingElapsedMs = recordingElapsedMs,
            )
        }
    }
}

/** "Import audio" without its label: padding 20 + 20, the 20 dp icon and the 8 dp gap. */
private val ImportChrome = 68.dp

/** The Record button without its label: padding 22 + 26, the 18 dp circle and the 10 dp gap. */
private val RecordChrome = 76.dp

/** The least space between Import and Record. */
private val BarGap = 8.dp

@Preview
@Composable
internal fun LibraryBottomBarPreview() {
    CassiniTheme {
        Column {
            LibraryBottomBar(onImport = {}, onRecord = {})
            LibraryBottomBar(onImport = {}, onRecord = {}, recordingElapsedMs = 1_447_000)
        }
    }
}
