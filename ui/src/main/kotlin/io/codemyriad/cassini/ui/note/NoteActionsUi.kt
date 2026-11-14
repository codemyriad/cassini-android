package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.Immutable
import io.codemyriad.cassini.ui.format.DayLabel
import java.time.LocalTime

/**
 * The Note actions sheet (board 06, features.md section 6; ui.md section 7). The Library and the Note screen both
 * show it. The meta line is "<recordedDay>, <recordedAt> · <duration> · <speakers> speakers"; the speaker part is
 * left out for plain notes and at 0 speakers.
 *
 * [textExportEnabled] shows the text formats (Markdown, then Plain text and the two subtitle formats under More
 * formats; L-11, cassini-ux D99). It is false for a meeting without a completed transcript (a plain note, a
 * transcript queued, running or failed, the empty transcript of an audio-only Cassini file) and for a transcript this
 * app cannot read ([transcriptUnreadable]); the sheet then hides them and says why. [busyRow] shows the progress of a
 * write in that row. [recording] hides Move to trash (the note is the active capture). [starred] makes the star
 * row Unstar instead of Star (L-34, D99).
 *
 * ui.md writes `recordedAt` as one value; here it is the day the ViewModel classified ([recordedDay]) and the time
 * of day, as everywhere else (ui.md section 2).
 */
@Immutable
data class NoteActionsUi(
    val noteId: String,
    val title: String,
    val recordedDay: DayLabel,
    val recordedAt: LocalTime,
    val durationMs: Long,
    val speakers: Int,
    val documentKind: DocumentKindUi,
    val textExportEnabled: Boolean,
    val transcribeRow: TranscribeRowUi,
    val busyRow: NoteAction? = null,
    val recording: Boolean = false,
    val recordedLabel: String? = null,
    val transcriptUnreadable: Boolean = false,
    val starred: Boolean = false,
)

/** What "Save a copy" saves first: the [Document] (the Cassini file, `.opus`) or the [Plain] audio file. */
sealed interface DocumentKindUi {
    data object Document : DocumentKindUi

    /**
     * A plain audio note; [ext] is the file extension without the dot ("opus", "m4a", "mp3"). An [imported] one is
     * offered as "Original audio" (the imported bytes, unchanged; L-11), an own recording as "Audio file".
     */
    @Immutable
    data class Plain(val ext: String, val imported: Boolean = false) : DocumentKindUi
}

/** The form of the transcribe row (features.md section 6). */
sealed interface TranscribeRowUi {
    /** A plain note: "Transcribe". */
    data object Transcribe : TranscribeRowUi

    /** A document: "Transcribe again" — "Adds version [nextVersion]." plus "Speaker names carry over." when [namesCarry]. */
    @Immutable
    data class TranscribeAgain(val nextVersion: Int, val namesCarry: Boolean) : TranscribeRowUi

    /**
     * A document that cannot take a new version here (UNVERIFIED or UNKNOWN_FORMAT, architecture 12): "Transcribe
     * again", disabled, with the reason.
     */
    data object TranscribeAgainUnavailable : TranscribeRowUi

    /** A running job: "Stop and keep progress". */
    data object Stop : TranscribeRowUi

    /** A queued job: "Remove from queue". */
    data object RemoveFromQueue : TranscribeRowUi

    /** A paused job: "Resume" and "Start over". */
    data object ResumeOrStartOver : TranscribeRowUi
}

/** A row of the Note actions sheet; the sheet sends it as its event. */
enum class NoteAction {
    SAVE_DOCUMENT,
    SAVE_TEXT,
    SAVE_MARKDOWN,
    SAVE_SRT,
    SAVE_VTT,
    SHARE_DOCUMENT,
    SHARE_TEXT,
    SHARE_MARKDOWN,
    SHARE_SRT,
    SHARE_VTT,
    RENAME,
    STAR,
    UNSTAR,
    TRANSCRIBE,
    TRANSCRIBE_AGAIN,
    STOP,
    REMOVE_FROM_QUEUE,
    RESUME,
    START_OVER,
    DOCUMENT_INFO,
    MOVE_TO_TRASH,
}

/** What an export writes (features.md section 6): the Cassini file or plain audio, Markdown, text, SubRip or WebVTT. */
enum class ExportKind { DOCUMENT, TEXT, MARKDOWN, SRT, VTT }
