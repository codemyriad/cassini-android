package io.codemyriad.cassini.ui.library

import androidx.compose.runtime.Immutable
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.components.ImportRowUi
import io.codemyriad.cassini.ui.components.NoteRowUi
import io.codemyriad.cassini.ui.components.SnackbarSpec
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.format.ModelPartUi
import io.codemyriad.cassini.ui.format.TimeLeftUi
import io.codemyriad.cassini.ui.note.InfoUi
import io.codemyriad.cassini.ui.note.NoteActionsUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * The Library (board 00, features.md section 1; ui.md section 7).
 *
 * - [query] is the search field text. While searching, [results] (the flat list, newest first, possibly empty)
 *   replaces [sections]; null means no search.
 * - The activity area shows [recording], then [progress], then [banners] (the empty and no-match banners too).
 * - [imports] are the import cards, oldest first, above the notes (L-25, W4.5 and W4.6): "Importing…" while an import
 *   runs, "Needs attention" when it failed.
 * - [snackbar] is the "moved to trash" snackbar; [sheet] the Note actions sheet of a long-pressed row, or the
 *   subtitle chooser or Document info that a row of it opens; [dialog] a rename or a confirmation from that sheet.
 *
 * [query] is the text of a field. The field keeps its own copy and sends each change as [LibraryEvent.Query]; it
 * takes a different text from the state only when that text is not one it sent (SyncedText). Thus the
 * ViewModel can update this value through its StateFlow.
 */
@Immutable
data class LibraryUiState(
    val loading: Boolean = false,
    val query: String = "",
    val searchFilters: SearchFiltersUi = SearchFiltersUi(),
    val recording: RecordingPillUi? = null,
    val progress: ProgressBannerUi? = null,
    val banners: ImmutableList<BannerSpec> = persistentListOf(),
    val imports: ImmutableList<ImportRowUi> = persistentListOf(),
    val sections: ImmutableList<DaySection> = persistentListOf(),
    val results: ImmutableList<NoteRowUi>? = null,
    val snackbar: SnackbarSpec? = null,
    val sheet: LibrarySheet? = null,
    val dialog: LibraryDialog? = null,
    val hero: FirstRunHeroUi? = null,
)

/**
 * The first-run hero (L-27, D-975) that replaces the empty-Library banner: "Every word written down." over a dimmed
 * "None of it uploaded.", which carries the "(except for encrypted cloud backup)" qualifier while [cloudBackup] is on
 * (D-937), then the wave, the how-to line and the [privacy] line (PARITY Wave 6 contract sections 1-2, D98).
 */
@Immutable
data class FirstRunHeroUi(val cloudBackup: Boolean, val privacy: FirstRunPrivacy)

/** The first-run privacy line, chosen by which automatic copies can leave the phone (D98). */
enum class FirstRunPrivacy {
    /** Cloud backup on: "Written down on this phone. Never uploaded, except to your encrypted cloud backup." */
    WITH_BACKUP,

    /** Cloud backup off, device-to-device transfer on: "Transcription runs on this phone." */
    PROCESSING,

    /** Neither: "Written down on this phone. The audio never leaves it." */
    PHONE_ONLY,
}

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
 * The open sheet of the Library. ui.md section 7 gives only the actions sheet; the Library shows the full Note actions
 * sheet (features.md section 1), so the rows that open a second overlay on the Note screen open it here too:
 * Document info opens [Info] (drawn as a dialog, as on the Note screen).
 */
sealed interface LibrarySheet {
    @Immutable
    data class Actions(val ui: NoteActionsUi) : LibrarySheet

    /** Document info of note [id]. */
    @Immutable
    data class Info(val id: String, val ui: InfoUi) : LibrarySheet
}

/**
 * A Library dialog. ui.md section 7 lists [Rename]; [StartOver], [TranscribeFresh] and [StopAndTrash] are the
 * confirmations of the actions sheet rows (features.md sections 3.2 and 6), added here because the sheet also opens
 * from the Library.
 */
sealed interface LibraryDialog {
    @Immutable
    data class Rename(val id: String, val title: String) : LibraryDialog

    @Immutable
    data class StartOver(val id: String) : LibraryDialog

    /** "Transcribe again? Earlier versions can't be kept because the audio changed." (a non-OK document). */
    @Immutable
    data class TranscribeFresh(val id: String) : LibraryDialog

    @Immutable
    data class StopAndTrash(val id: String) : LibraryDialog
}

/** D14 controls, deliberately absent from saved state. */
@Immutable
data class SearchFiltersUi(
    val speaker: String? = null,
    val date: SearchDateUi = SearchDateUi.ANY,
    val recordedByMe: Boolean = false,
    val speakers: ImmutableList<String> = persistentListOf(),
)

enum class SearchDateUi { ANY, TODAY, WEEK, MONTH }
