// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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
    val label = if (recordingElapsedMs == null) {
        stringResource(R.string.ui_library_record)
    } else {
        stringResource(R.string.ui_library_recording_fab, Durations.clock(recordingElapsedMs))
    }
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
 * While a recording runs (no board), "Recording · 24:07" does not fit next to "Import audio" at 390 dp, so Import
 * becomes a 52 dp round icon button with the same description. The Record button never pushes Import off: its
 * label ends with an ellipsis if the width still runs out (large fonts).
 */
@Composable
fun LibraryBottomBar(
    onImport: () -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
    recordingElapsedMs: Long? = null,
) {
    val bg = CassiniTheme.colors.bg
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to bg.copy(alpha = 0f), 0.4f to bg))
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val importLabel = stringResource(R.string.ui_library_import)
        if (recordingElapsedMs == null) {
            SecondaryButton(
                text = importLabel,
                onClick = onImport,
                size = SecondaryButtonSize.Large,
                glyph = Glyph.Import,
            )
        } else {
            ImportIconButton(importLabel, onImport)
        }
        Spacer(Modifier.width(8.dp))
        RecordFab(
            onClick = onRecord,
            modifier = Modifier.weight(1f, fill = false),
            recordingElapsedMs = recordingElapsedMs,
        )
    }
}

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
