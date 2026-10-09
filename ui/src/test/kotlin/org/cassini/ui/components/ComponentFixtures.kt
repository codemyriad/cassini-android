package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.format.DayLabel
import org.cassini.ui.theme.CassiniTheme
import java.time.LocalDate
import java.time.LocalTime

/** The Library rows of board 00 plus one row per other status, as (row, day) pairs. */
internal object ComponentFixtures {
    val weekly = NoteRowUi(
        id = "weekly",
        title = "Weekly product sync",
        durationMs = 2_893_000,
        time = LocalTime.of(10, 2),
        status = NoteStatusUi.Transcribing,
        highlighted = true,
    )
    val interview = NoteRowUi(
        id = "interview",
        title = "Interview — Marta",
        durationMs = 4_325_000,
        time = LocalTime.of(8, 40),
        status = NoteStatusUi.Queued,
    )
    val supplier = NoteRowUi(
        id = "supplier",
        title = "Call with supplier",
        durationMs = 1_430_000,
        time = LocalTime.of(15, 10),
        status = NoteStatusUi.Transcribed(speakers = 3, words = 4_812),
        preview = Highlighted(
            text = "quindi la consegna slitta a giovedì, but the price stays as agreed in the March order",
            leadingEllipsis = true,
            trailingEllipsis = true,
        ),
    )
    val board = NoteRowUi(
        id = "board",
        title = "Board review prep",
        durationMs = 2_052_000,
        time = LocalTime.of(11, 0),
        status = NoteStatusUi.Paused(atMs = 760_000),
    )
    val voiceNote = NoteRowUi(
        id = "voice",
        title = "Voice note 8 Oct, 08:14",
        durationMs = 161_000,
        time = LocalTime.of(8, 14),
        status = NoteStatusUi.AudioOnly,
    )

    /** Board 00's five rows with their day labels. */
    val boardRows = listOf(
        weekly to DayLabel.Today,
        interview to DayLabel.Today,
        supplier to DayLabel.Yesterday,
        board to DayLabel.Yesterday,
        voiceNote to DayLabel.Yesterday,
    )

    /** The statuses board 00 does not show, and a search result with highlights and the day in the meta line. */
    val otherRows = listOf(
        NoteRowUi(
            id = "rec",
            title = "Standup",
            durationMs = 1_447_000,
            time = LocalTime.of(10, 6),
            status = NoteStatusUi.Recording,
        ) to DayLabel.Today,
        NoteRowUi(
            id = "failed",
            title = "Customer interview with a long title that needs a second line to fit",
            durationMs = 3_605_000,
            time = LocalTime.of(9, 15),
            status = NoteStatusUi.Failed,
        ) to DayLabel.Today,
        NoteRowUi(
            id = "silent",
            title = "Pocket recording",
            durationMs = 42_000,
            time = LocalTime.of(18, 30),
            status = NoteStatusUi.Transcribed(speakers = 0, words = 0),
        ) to DayLabel.Yesterday,
        NoteRowUi(
            id = "unplayable",
            title = "Recovered recording 6 Oct",
            durationMs = 312_000,
            time = LocalTime.of(7, 55),
            status = NoteStatusUi.Unplayable,
        ) to DayLabel.Date(LocalDate.of(2026, 10, 6), withYear = false),
        NoteRowUi(
            id = "unreadable",
            title = "Imported lecture",
            durationMs = 5_400_000,
            time = LocalTime.of(14, 0),
            status = NoteStatusUi.Unreadable,
        ) to DayLabel.Date(LocalDate.of(2026, 10, 5), withYear = false),
        NoteRowUi(
            id = "newer",
            title = "Shared document",
            durationMs = 600_000,
            time = LocalTime.of(16, 20),
            status = NoteStatusUi.NewerFormat,
        ) to DayLabel.Date(LocalDate.of(2025, 12, 1), withYear = true),
        NoteRowUi(
            id = "result",
            title = "Call with supplier",
            titleRanges = persistentListOf(10..17),
            durationMs = 1_430_000,
            time = LocalTime.of(15, 10),
            date = LocalDate.of(2026, 10, 8),
            status = NoteStatusUi.Transcribed(speakers = 3, words = 4_812),
            preview = Highlighted(
                text = "quindi la consegna slitta a giovedì, but the price stays as agreed",
                ranges = persistentListOf(10..17, 28..34),
                leadingEllipsis = true,
                trailingEllipsis = true,
            ),
        ) to DayLabel.Yesterday,
    )
}

/** A 390 dp strip on bg with 8 dp above and below. */
@Composable
internal fun Strip(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .width(390.dp)
            .background(CassiniTheme.colors.bg)
            .padding(vertical = 8.dp),
        content = content,
    )
}

/** A section label on a strip (test copy, not a resource). */
@Composable
internal fun Label(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        style = CassiniTheme.type.badge,
        color = CassiniTheme.colors.text4,
    )
}

/** A 16 dp padded group of items with [gap] between them. */
@Composable
internal fun Padded(gap: Int = 8, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(gap.dp),
        content = content,
    )
}
