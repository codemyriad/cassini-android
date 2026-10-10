package org.cassini.ui.screens

import org.cassini.ui.BoardTest
import org.cassini.ui.components.DialogBoardFrame
import org.cassini.ui.components.RenameDialogContent
import org.cassini.ui.fixtures.VariantFixtures
import org.cassini.ui.library.LibraryDialog
import org.cassini.ui.library.LibraryScreen
import org.cassini.ui.note.NoteScreen
import org.junit.Test

/**
 * The screen variants of docs/design/features.md (acceptance lists) and their states without a board, as goldens
 * only (`variants/<name>.png`, ui.md section 10.2). Dialogs render inline in `DialogBoardFrame`.
 */
class StateVariantsTest : BoardTest() {
    private fun library(name: String) = board("variants", name) {
        LibraryScreen(VariantFixtures.library.getValue(name), onEvent = {})
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

    // Read & listen (features.md section 3.3).

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
