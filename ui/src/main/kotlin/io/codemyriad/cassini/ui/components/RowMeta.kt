@file:Suppress("FunctionNaming", "MagicNumber") // Approved Row-Android metadata and people glyph geometry.

package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Dates
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.theme.CassiniTheme

@Composable
internal fun RowMeta(row: NoteRowUi, day: DayLabel, status: RowStatus, onAction: (NoteRowAction) -> Unit) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    FlowRow(
        modifier = Modifier.heightIn(min = 22.dp).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.recordedLabel ?: if (day == DayLabel.Unknown) {
                Dates.dayLabel(day)
            } else {
                row.day?.let { Dates.dayTime(it, row.time) } ?: Dates.time(row.time)
            },
            style = t.monoTime,
            color = c.text2,
        )
        if (row.status is NoteStatusUi.Transcribed) {
            FinishedPill(row.status)
        } else {
            status.kind?.let { StatusChip(status.text, it) }
        }
        RecoveryAction(status, row.title, onAction)
    }
}

@Composable
private fun RecoveryAction(status: RowStatus, title: String, onAction: (NoteRowAction) -> Unit) {
    val action = status.suffixAction ?: return
    val text = when (action) {
        NoteRowAction.RESUME -> stringResource(R.string.ui_row_resume)
        NoteRowAction.TRY_AGAIN -> stringResource(R.string.ui_row_try_again)
        else -> return
    }
    val description = stringResource(
        if (action == NoteRowAction.RESUME) R.string.ui_row_resume_description else R.string.ui_row_try_again_description,
        title,
    )
    Text(
        text,
        Modifier.clickable(role = Role.Button, onClick = { onAction(action) })
            .semantics { contentDescription = description },
        style = CassiniTheme.type.caption.copy(fontWeight = FontWeight.W600),
        color = CassiniTheme.colors.ink,
        maxLines = 1,
    )
}

/** A finished row keeps its spoken state even though the visual status chip is gone (L-52). */
@Composable
internal fun spokenSuffix(state: NoteStatusUi, status: RowStatus): String? = when {
    state is NoteStatusUi.Transcribed && state.words > 0 && state.speakers == 1 ->
        Counts.plural(R.plurals.ui_row_speaker_count, 1)
    else -> status.suffix
}

@Composable
private fun FinishedPill(status: NoteStatusUi.Transcribed) {
    if (status.words == 0) {
        NeutralRowPill(stringResource(R.string.ui_row_no_speech), people = false)
    } else if (status.speakers >= 1) {
        NeutralRowPill(Counts.plural(R.plurals.ui_row_speaker_count, status.speakers), people = true)
    }
}

/** L-52 counts are neutral pills, not status badges. No-speech is intentionally text-only. */
@Composable
private fun NeutralRowPill(text: String, people: Boolean) {
    val c = CassiniTheme.colors
    Row(
        Modifier.heightIn(min = 22.dp).background(c.ink.copy(alpha = 0.1f), CircleShape).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (people) PeopleGlyph()
        Text(text, style = CassiniTheme.type.chip, color = c.text2)
    }
}

/** Exact people silhouette from Row-Android.dc.html (20 by 16 viewport, displayed at 13 by 11dp). */
@Composable
private fun PeopleGlyph() {
    val color = CassiniTheme.colors.text2
    Canvas(Modifier.size(13.dp, 11.dp)) {
        val sx = size.width / 20f
        val sy = size.height / 16f
        drawOval(color, Offset(3.8f * sx, sy), androidx.compose.ui.geometry.Size(6.4f * sx, 6.4f * sy))
        drawOval(color, Offset(11.6f * sx, 2.2f * sy), androidx.compose.ui.geometry.Size(5.2f * sx, 5.2f * sy))
        val bodies = Path().apply {
            moveTo(0.8f * sx, 15f * sy)
            cubicTo(0.8f * sx, 11.4f * sy, 3.6f * sx, 9f * sy, 7f * sx, 9f * sy)
            cubicTo(10.4f * sx, 9f * sy, 13.2f * sx, 11.4f * sy, 13.2f * sx, 15f * sy)
            close()
            moveTo(14.6f * sx, 9.2f * sy)
            cubicTo(17.4f * sx, 9.4f * sy, 19.4f * sx, 11.6f * sy, 19.4f * sx, 15f * sy)
            lineTo(14.8f * sx, 15f * sy)
            cubicTo(14.8f * sx, 12.8f * sy, 14.2f * sx, 10.8f * sy, 12.7f * sx, 9.6f * sy)
            cubicTo(13.3f * sx, 9.3f * sy, 13.9f * sx, 9.2f * sy, 14.6f * sx, 9.2f * sy)
            close()
        }
        drawPath(bodies, color)
    }
}
