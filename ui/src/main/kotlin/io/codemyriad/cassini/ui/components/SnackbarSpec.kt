package io.codemyriad.cassini.ui.components

import androidx.compose.runtime.Immutable

/** The snackbars the screens show (ui.md section 7). */
enum class SnackbarKind {
    /** "“<title>” moved to trash", with Undo; [SnackbarSpec.text] is the title. */
    MOVED_TO_TRASH,

    /** "Saved “<file>”"; [SnackbarSpec.text] is the file name. */
    SAVED_FILE,

    /** "Couldn't save the file". */
    SAVE_FAILED,

    /** "Couldn't share the file": the share file could not be written. */
    SHARE_FAILED,

    /** "Can't play" (the status text of an unplayable note): the player failed; Play opens it again. */
    PLAYBACK_FAILED,

    /** "Speaker 5 is now a background voice", with Undo; [SnackbarSpec.text] is the speaker's name (D112). */
    BACKGROUND_VOICE,

    /** "4 background voices", with Undo; [SnackbarSpec.count] is the number (D112). */
    BACKGROUND_VOICES,

    /** "Named Marta · 4 background voices", with Undo; [SnackbarSpec.text] the name, [SnackbarSpec.count] (D112). */
    NAMED_AND_BACKGROUND,
}

/**
 * One snackbar (ui.md section 7). [durationMs] is computed by the ViewModel from
 * `AccessibilityManager.getRecommendedTimeoutMillis(10_000, …)`; [undo] adds the Undo action. [id] tells two
 * snackbars with the same text apart, so the second one restarts the timeout.
 */
@Immutable
data class SnackbarSpec(
    val kind: SnackbarKind,
    val text: String? = null,
    val count: Int = 0,
    val undo: Boolean = false,
    val durationMs: Long,
    val id: Long = 0,
)
