package org.cassini.ui.screens

import org.cassini.ui.BoardTest
import org.cassini.ui.components.DialogBoardFrame
import org.cassini.ui.components.RenameDialogContent
import org.cassini.ui.fixtures.VariantFixtures
import org.cassini.ui.library.LibraryDialog
import org.cassini.ui.library.LibraryScreen
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.recording.RecordingScreen
import org.junit.Test

/**
 * The screen variants of docs/design/features.md (acceptance lists) and their states without a board, as goldens
 * only (`variants/<name>.png`, ui.md section 10.2). Dialogs render inline in `DialogBoardFrame`.
 */
class StateVariantsTest : BoardTest() {
    private fun library(name: String) = board("variants", name) {
        LibraryScreen(VariantFixtures.library.getValue(name), onEvent = {})
    }

    private fun recording(name: String) = board("variants", name) {
        RecordingScreen(VariantFixtures.recording.getValue(name), onEvent = {})
    }

    private fun note(name: String) = board("variants", name) {
        NoteScreen(VariantFixtures.transcribing.getValue(name), onEvent = {})
    }

    private fun read(name: String) = board("variants", name) {
        NoteScreen(VariantFixtures.read.getValue(name), onEvent = {})
    }

    // Library (features.md section 1).

    @Test fun libraryLoading() = library("library-loading")

    @Test fun libraryEmpty() = library("library-empty")

    @Test fun libraryNoResults() = library("library-no-results")

    @Test fun librarySearch() = library("library-search")

    @Test fun libraryRecordingCard() = library("library-recording-card")

    @Test fun libraryRecordingPaused() = library("library-recording-paused")

    @Test fun libraryProgressCalculating() = library("library-progress-calculating")

    @Test fun libraryProgressWaiting() = library("library-progress-waiting")

    @Test fun libraryProgressDownloading() = library("library-progress-downloading")

    @Test fun libraryProgressChecking() = library("library-progress-checking")

    @Test fun libraryProgressStandalone() = library("library-progress-standalone")

    @Test fun libraryNotices() = library("library-notices")

    @Test fun librarySnackbar() = library("library-snackbar")

    @Test fun libraryOtherStatuses() = library("library-other-statuses")

    @Test fun libraryRename() = board("variants", "library-rename") {
        val dialog = VariantFixtures.library.getValue("library-rename").dialog as LibraryDialog.Rename
        DialogBoardFrame {
            RenameDialogContent(dialog.title, dialog.title, onValueChange = {}, onConfirm = {}, onDismiss = {})
        }
    }

    // Recording (features.md section 2).

    @Test fun recordingPaused() = recording("recording-paused")

    @Test fun recordingPausedOtherApp() = recording("recording-paused-other-app")

    @Test fun recordingPermissionOff() = recording("recording-permission-off")

    @Test fun recordingStarting() = recording("recording-starting")

    @Test fun recordingStorageLow() = recording("recording-storage-low")

    @Test fun recordingNearLimit() = recording("recording-near-limit")

    @Test fun recordingCapHint() = recording("recording-cap-hint")

    // Note modes other than Read & listen (features.md sections 3, 3.1 and 3.2).

    @Test fun transcribingQueued() = note("transcribing-queued")

    @Test fun transcribingPaused() = note("transcribing-paused")

    @Test fun transcribingPausedEarly() = note("transcribing-paused-early")

    @Test fun transcribingQueueHeld() = note("transcribing-queue-held")

    @Test fun transcribingDownloading() = note("transcribing-downloading")

    @Test fun transcribingChecking() = note("transcribing-checking")

    @Test fun transcribingWaiting() = note("transcribing-waiting")

    @Test fun transcribingStartingLowMemory() = note("transcribing-starting-low-memory")

    @Test fun transcribingSaving() = note("transcribing-saving")

    @Test fun transcribingNoConnection() = note("transcribing-no-connection")

    @Test fun transcribingFailed() = note("transcribing-failed")

    @Test fun transcribingFailedCheckpoint() = note("transcribing-failed-checkpoint")

    @Test fun transcribingMemory() = note("transcribing-memory")

    @Test fun transcribingCheckpointMismatch() = note("transcribing-checkpoint-mismatch")

    @Test fun noteAudioOnly() = note("note-audio-only")

    @Test fun noteUnplayable() = note("note-unplayable")

    // Read & listen (features.md section 3.3).

    @Test fun readQueuedOverDocument() = read("read-queued-over-document")

    @Test fun readFailedOverDocument() = read("read-failed-over-document")

    @Test fun readNotVerified() = read("read-not-verified")

    @Test fun readAudioChanged() = read("read-audio-changed")

    @Test fun readUnreadable() = read("read-unreadable")

    @Test fun readNewerFormat() = read("read-newer-format")

    @Test fun readVerifiedBanner() = read("read-verified-banner")

    @Test fun readOneVoice() = read("read-one-voice")

    @Test fun readSearch() = read("read-search")

    @Test fun readSearchNoMatches() = read("read-search-no-matches")

    @Test fun readFollowPaused() = read("read-follow-paused")

    @Test fun readSnackbarSaved() = read("read-snackbar-saved")
}
