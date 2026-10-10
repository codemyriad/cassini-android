package org.cassini.ui.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.format.DayLabel
import java.time.LocalTime

/**
 * A note's status in the Library (features.md section 1, `NoteStatus.of` in :core). The row turns it into the chip
 * and the suffix; see [NoteRow].
 */
sealed interface NoteStatusUi {
    data object Recording : NoteStatusUi

    data object Transcribing : NoteStatusUi

    data object Queued : NoteStatusUi

    /** Paused with [atMs] of audio done ("Paused at 12:40 · Resume"). */
    @Immutable
    data class Paused(val atMs: Long) : NoteStatusUi

    data object Failed : NoteStatusUi

    data object Unplayable : NoteStatusUi

    data object Unreadable : NoteStatusUi

    data object NewerFormat : NoteStatusUi

    /** "Transcribed", with "· 3 speakers" from 2 [speakers] and "· No speech" at 0 [words]. */
    @Immutable
    data class Transcribed(val speakers: Int, val words: Int) : NoteStatusUi

    data object AudioOnly : NoteStatusUi
}

/**
 * Text with highlighted [ranges] (search matches, drawn in weight 600 on accentTint) and the ellipses of a preview
 * cut from a longer transcript. The ranges index [text], without the ellipses.
 */
@Immutable
data class Highlighted(
    val text: String,
    val ranges: ImmutableList<IntRange> = persistentListOf(),
    val leadingEllipsis: Boolean = false,
    val trailingEllipsis: Boolean = false,
)

/**
 * One Library row (ui.md section 7). [titleRanges] highlight a title match. [day] is set in the flat search
 * results, where the meta line shows the day too; in day sections it is null (the section header names the day).
 * [highlighted] puts the row on surface (the running note).
 *
 * ui.md section 7 gives this field as `date: LocalDate?`. It is a [DayLabel] here, which the ViewModel classifies
 * with its clock (ui.md section 2: the UI never asks what today is), so a result from today reads "Today, 09:12".
 */
@Immutable
data class NoteRowUi(
    val id: String,
    val title: String,
    val titleRanges: ImmutableList<IntRange> = persistentListOf(),
    val durationMs: Long,
    val time: LocalTime,
    val day: DayLabel? = null,
    val status: NoteStatusUi,
    val preview: Highlighted? = null,
    val highlighted: Boolean = false,
)

/** What a Library row asks for: open, the long press, the inline suffixes and the TalkBack custom actions. */
enum class NoteRowAction { OPEN, LONG_PRESS, RESUME, TRY_AGAIN, RENAME, TRASH }
