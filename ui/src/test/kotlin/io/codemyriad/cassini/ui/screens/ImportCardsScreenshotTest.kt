package io.codemyriad.cassini.ui.screens

import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.components.ImportRowUi
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.library.LibraryScreen
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * L-25 list feedback (ALIGNMENT-PLAN W4.5 and W4.6; COMPONENTS.md section 7), light and dark: the Library during an
 * import of three files ("Importing…" cards with the neutral spinner badge) and with one failed import (its card kept
 * with the amber "Needs attention" badge and Dismiss).
 */
class ImportCardsScreenshotTest : BoardTest() {
    @Test fun importingThreeFilesLight() = library(dark = false, IMPORTING, "20-library-importing")

    @Test
    @Config(qualifiers = "+night")
    fun importingThreeFilesDark() = library(dark = true, IMPORTING, "20-library-importing")

    @Test fun oneFailedImportLight() = library(dark = false, FAILED, "21-library-import-failed")

    @Test
    @Config(qualifiers = "+night")
    fun oneFailedImportDark() = library(dark = true, FAILED, "21-library-import-failed")

    private fun library(dark: Boolean, imports: ImmutableList<ImportRowUi>, name: String) {
        val state = BoardFixtures.library.copy(imports = imports)
        board(if (dark) "dark" else "light", name, dark = dark) { LibraryScreen(state, onEvent = {}) }
    }

    private companion object {
        val IMPORTING = persistentListOf(
            ImportRowUi(1, "Quarterly review.m4a"),
            ImportRowUi(2, "Client call 14 Nov.mp3"),
            ImportRowUi(3, "Standup.wav"),
        )
        val FAILED = persistentListOf(ImportRowUi(4, "voice-note.ogg", failed = true))
    }
}
