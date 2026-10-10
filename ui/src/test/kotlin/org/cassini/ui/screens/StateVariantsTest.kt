package org.cassini.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import org.cassini.ui.BoardTest
import org.cassini.ui.components.DialogBoardFrame
import org.cassini.ui.components.MenuBoardFrame
import org.cassini.ui.components.SheetBoardFrame
import org.cassini.ui.fixtures.VariantFixtures
import org.cassini.ui.library.LibraryScreen
import org.cassini.ui.licenses.LicensesScreen
import org.cassini.ui.models.ModelDownloadScreen
import org.cassini.ui.note.MergeConfirmContent
import org.cassini.ui.note.MergeSheetContent
import org.cassini.ui.note.NameSpeakerSheetContent
import org.cassini.ui.note.NoteActionsSheetContent
import org.cassini.ui.note.NoteScreen
import org.cassini.ui.recording.RecordingScreen
import org.cassini.ui.settings.SettingsScreen
import org.cassini.ui.trash.TrashDialogContent
import org.cassini.ui.trash.TrashScreen
import org.cassini.ui.voices.SavedVoiceMenuItems
import org.cassini.ui.voices.SavedVoicesDialogContent
import org.cassini.ui.voices.SavedVoicesScreen
import org.junit.Test

/**
 * The screen variants of docs/design/features.md (acceptance lists) and their states without a board, as goldens
 * only (`variants/<name>.png`, ui.md section 10.2). Sheets render inline in `SheetBoardFrame` and dialogs in
 * `DialogBoardFrame`, over the plain scrim as boards 04 and 06 show them.
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

    private fun readOverlay(name: String) = board("variants", name) {
        NoteOverlayFrame(VariantFixtures.read.getValue(name))
    }

    private fun noteSheet(name: String) = board("variants", name) {
        NoteOverlayFrame(VariantFixtures.noteSheets.getValue(name))
    }

    private fun nameSheet(name: String) = board("variants", name) {
        SheetBoardFrame { NameSpeakerSheetContent(VariantFixtures.nameSheet.getValue(name), onEvent = {}) }
    }

    private fun actions(name: String) = board("variants", name) {
        SheetBoardFrame { NoteActionsSheetContent(VariantFixtures.noteActions.getValue(name), onAction = {}) }
    }

    private fun mergeConfirm(name: String) = board("variants", name) {
        val merge = VariantFixtures.merges.getValue(name)
        DialogBoardFrame { MergeConfirmContent(merge, requireNotNull(merge.confirm), onEvent = {}) }
    }

    private fun models(name: String) = board("variants", name) {
        ModelDownloadScreen(VariantFixtures.modelDownload.getValue(name), onEvent = {})
    }

    private fun settings(name: String) = board("variants", name) {
        SettingsScreen(VariantFixtures.settings.getValue(name), onEvent = {})
    }

    private fun voices(name: String) = board("variants", name) {
        SavedVoicesScreen(VariantFixtures.savedVoices.getValue(name), onEvent = {})
    }

    private fun trash(name: String) = board("variants", name) {
        TrashScreen(VariantFixtures.trash.getValue(name), onEvent = {})
    }

    private fun trashDialog(name: String) = board("variants", name) {
        val dialog = requireNotNull(VariantFixtures.trash.getValue(name).dialog)
        DialogBoardFrame { TrashDialogContent(dialog, onEvent = {}) }
    }

    private fun voicesDialog(name: String) = board("variants", name) {
        val dialog = requireNotNull(VariantFixtures.savedVoices.getValue(name).dialog)
        DialogBoardFrame { SavedVoicesDialogContent(dialog, onEvent = {}) }
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

    /**
     * The Library's own overlays, drawn by LibraryScreen in their windows over the list (the whole screen is
     * captured), so the goldens check that the Library state opens them.
     */
    @Test fun libraryActionsSheet() = board("variants", "library-actions-sheet", window = true) {
        LibraryScreen(VariantFixtures.library.getValue("library-actions-sheet"), onEvent = {})
    }

    @Test fun libraryRename() = board("variants", "library-rename", window = true) {
        LibraryScreen(VariantFixtures.library.getValue("library-rename"), onEvent = {})
    }

    // Recording (features.md section 2).

    @Test fun recordingPaused() = recording("recording-paused")

    @Test fun recordingPausedOtherApp() = recording("recording-paused-other-app")

    @Test fun recordingPermissionOff() = recording("recording-permission-off")

    @Test fun recordingStarting() = recording("recording-starting")

    @Test fun recordingStopping() = recording("recording-stopping")

    @Test fun recordingM4a() = recording("recording-m4a")

    @Test fun recordingStorageLow() = recording("recording-storage-low")

    @Test fun recordingNearLimit() = recording("recording-near-limit")

    @Test fun recordingCapHint() = recording("recording-cap-hint")

    // Note modes other than Read & listen (features.md sections 3, 3.1 and 3.2).

    @Test fun transcribingQueued() = note("transcribing-queued")

    @Test fun transcribingQueuedNext() = note("transcribing-queued-next")

    @Test fun transcribingNotificationsOff() = note("transcribing-notifications-off")

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

    // Read & listen sheets and dialogs (features.md sections 3.2, 3.4 and 6).

    @Test fun readSubtitleFormat() = readOverlay("read-subtitle-format")

    @Test fun readRename() = readOverlay("read-rename")

    @Test fun readTranscribeFresh() = readOverlay("read-transcribe-fresh")

    @Test fun readReadOnly() = readOverlay("read-read-only")

    @Test fun pausedStartOver() = readOverlay("paused-start-over")

    @Test fun transcribingStopAndTrash() = readOverlay("transcribing-stop-and-trash")

    @Test fun readVersions() = noteSheet("read-versions")

    @Test fun readDocumentInfo() = noteSheet("read-document-info")

    @Test fun readMerge() = noteSheet("read-merge")

    // Name a speaker (features.md section 4).

    @Test fun nameRenameSaved() = nameSheet("name-rename-saved")

    @Test fun nameNotAuto() = nameSheet("name-not-auto")

    @Test fun nameNoVoices() = nameSheet("name-no-voices")

    @Test fun nameNoPrint() = nameSheet("name-no-print")

    @Test fun nameNoModel() = nameSheet("name-no-model")

    @Test fun nameShowAll() = nameSheet("name-show-all")

    @Test fun nameDraft() = nameSheet("name-draft")

    @Test fun nameSampling() = nameSheet("name-sampling")

    // Merge sheet and its confirmation (features.md section 4, item 8).

    @Test fun mergeSheet() = board("variants", "merge-sheet") {
        SheetBoardFrame { MergeSheetContent(VariantFixtures.merges.getValue("merge-sheet"), onEvent = {}) }
    }

    @Test fun mergeConfirmSealed() = mergeConfirm("merge-confirm-sealed")

    @Test fun mergeConfirmDraft() = mergeConfirm("merge-confirm-draft")

    // Note actions (features.md section 6).

    @Test fun actionsPlain() = actions("actions-plain")

    @Test fun actionsUnreadable() = actions("actions-unreadable")

    @Test fun actionsStop() = actions("actions-stop")

    @Test fun actionsRemoveFromQueue() = actions("actions-remove-from-queue")

    @Test fun actionsResumeOrStartOver() = actions("actions-resume-or-start-over")

    @Test fun actionsBusy() = actions("actions-busy")

    @Test fun actionsRecording() = actions("actions-recording")

    @Test fun actionsNoNamesCarry() = actions("actions-no-names-carry")

    // Model download (features.md section 5).

    @Test fun modelsNoSpace() = models("models-no-space")

    @Test fun modelsNoConnection() = models("models-no-connection")

    @Test fun modelsWithoutNote() = models("models-without-note")

    @Test fun modelsBusy() = models("models-busy")

    @Test fun modelsVoicesOnly() = models("models-voices-only")

    @Test fun modelsSpeechSpeakers() = models("models-speech-speakers")

    // Settings (features.md section 11).

    @Test fun settingsNotificationsOff() = settings("settings-notifications-off")

    @Test fun settingsDownloading() = settings("settings-downloading")

    @Test fun settingsModelStatuses() = settings("settings-model-statuses")

    @Test fun settingsItalianNone() = settings("settings-italian-none")

    // Saved voices (features.md section 10).

    @Test fun voicesEmpty() = voices("voices-empty")

    @Test fun voicesStale() = voices("voices-stale")

    @Test fun voicesError() = voices("voices-error")

    @Test fun voicesErrorEmpty() = voices("voices-error-empty")

    @Test fun voicesUnderAMinute() = voices("voices-under-a-minute")

    /** The person menu, drawn inline by `MenuBoardFrame` at the options button of the row it belongs to. */
    @Test fun voicesMenu() {
        val state = VariantFixtures.savedVoices.getValue("voices-menu")
        val id = requireNotNull(state.menuFor)
        val name = state.people.first { it.id == id }.name
        var anchor by mutableStateOf<DpRect?>(null)
        show {
            val screen = @Composable { SavedVoicesScreen(state.copy(menuFor = null), onEvent = {}) }
            val at = anchor
            if (at == null) {
                screen()
            } else {
                MenuBoardFrame(anchor = at, screen = screen) { SavedVoiceMenuItems(id, onEvent = {}) }
            }
        }
        val bounds = rule.onNodeWithContentDescription("Options for $name").getBoundsInRoot()
        rule.runOnUiThread { anchor = bounds }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Rename…").assertExists()
        capture("variants", "voices-menu")
    }

    @Test fun voicesRename() = voicesDialog("voices-rename")

    @Test fun voicesForget() = voicesDialog("voices-forget")

    @Test fun voicesForgetEveryone() = voicesDialog("voices-forget-everyone")

    // Trash (features.md section 8).

    @Test fun trashEmpty() = trash("trash-empty")

    @Test fun trashPlain() = trash("trash-plain")

    @Test fun trashFromSettings() = trash("trash-from-settings")

    @Test fun trashYesterday() = trash("trash-yesterday")

    @Test fun trashDelete() = trashDialog("trash-delete")

    @Test fun trashEmptyAll() = trashDialog("trash-empty-all")

    // Licenses (no board; features.md section 11).

    @Test fun licenses() = board("variants", "licenses") {
        LicensesScreen(VariantFixtures.licenses.getValue("licenses"), onEvent = {})
    }

    @Test fun licensesText() = board("variants", "licenses-text") {
        LicensesScreen(VariantFixtures.licenses.getValue("licenses-text"), onEvent = {})
    }
}
