package org.cassini.ui.components

import androidx.compose.runtime.Immutable

/** The snackbars the screens show (ui.md section 7). */
enum class SnackbarKind {
    /** "“<title>” moved to trash", with Undo; [SnackbarSpec.text] is the title. */
    MOVED_TO_TRASH,

    /** "Saved “<file>”"; [SnackbarSpec.text] is the file name. */
    SAVED_FILE,

    /** "Couldn't save the file". */
    SAVE_FAILED,
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
    val undo: Boolean = false,
    val durationMs: Long,
    val id: Long = 0,
)
