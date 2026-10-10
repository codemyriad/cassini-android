package org.cassini.ui.library

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.components.NoteRowUi
import org.cassini.ui.components.SnackbarSpec
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.format.TimeLeftUi
import org.cassini.ui.note.NoteActionsUi

/**
 * The Library (board 00, features.md section 1; ui.md section 7).
 *
 * - [query] is the search field text. While searching, [results] (the flat list, newest first, possibly empty)
 *   replaces [sections]; null means no search.
 * - The activity area shows [recording], then [progress], then [banners] (the empty and no-match banners too).
 * - [snackbar] is the "moved to trash" snackbar; [sheet] the Note actions sheet of a long-pressed row; [dialog] a
 *   rename or a confirmation from that sheet.
 */
@Immutable
data class LibraryUiState(
    val loading: Boolean = false,
    val query: String = "",
    val recording: RecordingPillUi? = null,
    val progress: ProgressBannerUi? = null,
    val banners: ImmutableList<BannerSpec> = persistentListOf(),
    val sections: ImmutableList<DaySection> = persistentListOf(),
    val results: ImmutableList<NoteRowUi>? = null,
    val snackbar: SnackbarSpec? = null,
    val sheet: NoteActionsUi? = null,
    val dialog: LibraryDialog? = null,
)

/** The recording card and the Record button's recording form: "Recording · <title>" and the elapsed time. */
@Immutable
data class RecordingPillUi(val title: String, val elapsedMs: Long, val paused: Boolean)

/**
 * The transcription banner (features.md section 1, item 4). [noteId] is the running note (null for a standalone
 * download, which opens Settings). [title] is the note title of "Transcribing <title>" or "Getting ready to
 * transcribe <title>"; null with no note ("Downloading models"). [percent] null hides the percent; [progress]
 * (0–1) null draws no fill. [queued] adds "· N more in queue" from 1.
 */
@Immutable
data class ProgressBannerUi(
    val noteId: String?,
    val title: String?,
    val percent: Int?,
    val progress: Float?,
    val line: ProgressLine,
    val queued: Int = 0,
)

/** Line 2 of the transcription banner. */
sealed interface ProgressLine {
    /** "About 6–9 min left", "Calculating time left…", "Finishing…". */
    @Immutable
    data class TimeLeft(val timeLeft: TimeLeftUi) : ProgressLine

    /** "Finishing the previous note…". */
    data object Waiting : ProgressLine

    /** "Downloading speech recognition — 46%". */
    @Immutable
    data class Downloading(val part: ModelPartUi, val percent: Int) : ProgressLine

    /** "Checking files — 80%". */
    @Immutable
    data class Checking(val percent: Int) : ProgressLine
}

/** One day group: the overline [label] and its rows, newest first. */
@Immutable
data class DaySection(val label: DayLabel, val rows: ImmutableList<NoteRowUi>)

/**
 * A Library dialog. ui.md section 7 lists [Rename]; [StartOver] and [StopAndTrash] are the confirmations of the
 * actions sheet rows (features.md sections 3.2 and 6), added here because the sheet also opens from the Library.
 */
sealed interface LibraryDialog {
    @Immutable
    data class Rename(val id: String, val title: String) : LibraryDialog

    @Immutable
    data class StartOver(val id: String) : LibraryDialog

    @Immutable
    data class StopAndTrash(val id: String, val title: String) : LibraryDialog
}
