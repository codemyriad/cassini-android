@file:Suppress("FunctionNaming", "MagicNumber") // Approved Row-Android metadata geometry.

package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

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
        // L-34: a starred row leads its meta line with the star; the row's description says "starred".
        if (row.starred) CassiniIcon(Glyph.StarFilled, 14.dp, 0f, c.accent, contentDescription = null)
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
            // L-10: the outline "Imported" badge comes before the speaker count; any other badge replaces it.
            if (row.imported) StatusChip(stringResource(StatusBadgeKind.Received.label), StatusBadgeKind.Received)
            FinishedDetail(row.status)
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

/**
 * The speaker count of a finished meeting, or "No speech", as plain grey text after the time and any status pill (QA
 * 25 Nov, as on the Mac): every status is a pill, the details are not, and there is no speaker icon.
 */
@Composable
private fun FinishedDetail(status: NoteStatusUi.Transcribed) {
    val text = when {
        status.words == 0 -> stringResource(R.string.ui_row_no_speech)
        status.speakers >= 1 -> Counts.plural(R.plurals.ui_row_speaker_count, status.speakers)
        else -> return
    }
    Text(text, style = CassiniTheme.type.chip, color = CassiniTheme.colors.text2)
}
