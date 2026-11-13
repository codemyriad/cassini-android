package io.codemyriad.cassini.ui.screens

import org.junit.Test
import org.robolectric.annotation.Config

/** The gallery's core states and processing failures in dark; light cases stay in StateVariantsTest. */
@Config(qualifiers = "+night")
class DarkStateVariantsTest : VariantBoardTest() {
    override val variantDirectory: String = "variants-dark"
    override val variantDark: Boolean = true

    @Test fun libraryEmpty() = library("library-empty")

    @Test fun librarySearch() = library("library-search")

    @Test fun libraryRecordingCard() = library("library-recording-card")

    @Test fun readSearch() = read("read-search")

    @Test fun readSearchNoMatches() = read("read-search-no-matches")

    @Test fun nameDraft() = nameSheet("name-draft")

    @Test fun voicesEmpty() = voices("voices-empty")

    @Test fun voicesError() = voices("voices-error")

    @Test fun trashPlain() = trash("trash-plain")

    @Test fun settingsLanguageSheet() = languageSheet()

    @Test fun transcribingNoConnection() = note("transcribing-no-connection")

    @Test fun transcribingFailed() = note("transcribing-failed")

    @Test fun transcribingFailedCheckpoint() = note("transcribing-failed-checkpoint")

    @Test fun transcribingMemory() = note("transcribing-memory")

    @Test fun transcribingCheckpointMismatch() = note("transcribing-checkpoint-mismatch")

    @Test fun transcribingStartingLowMemory() = transcribingDetails("transcribing-starting-low-memory")

    @Test fun transcribingDownloading() = transcribingDetails("transcribing-downloading")

    @Test fun transcribingChecking() = transcribingDetails("transcribing-checking")

    @Test fun transcribingWaiting() = transcribingDetails("transcribing-waiting")

    @Test fun transcribingNotificationsOff() = transcribingDetails("transcribing-notifications-off")

    @Test fun readFailedOverDocument() = read("read-failed-over-document")

    @Test fun readNotVerified() = read("read-not-verified")

    @Test fun modelsNoSpace() = models("models-no-space")

    @Test fun actionsPlain() = actions("actions-plain")
}
