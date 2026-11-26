package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.fixtures.BackgroundVoicesFixtures
import io.codemyriad.cassini.ui.note.NameSheetUi
import io.codemyriad.cassini.ui.note.NoteScreen
import io.codemyriad.cassini.ui.note.NoteSheet
import io.codemyriad.cassini.ui.screens.FormFactorBoardTest.Companion.FOLDABLE_UNFOLDED
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Background voices and the speaker row (cassini-ux D112 / L-86, D-1168): the Name sheet with the row, the switch on,
 * the folded and opened transcript and the Undo snackbar, light and dark, on a phone and unfolded.
 */
class BackgroundVoicesScreenshotTest : BoardTest() {
    @Test fun sheetRowLight() = sheet(false, BackgroundVoicesFixtures.sheetRow, "d112-name-row")

    @Test
    @Config(qualifiers = "+night")
    fun sheetRowDark() = sheet(true, BackgroundVoicesFixtures.sheetRow, "d112-name-row")

    @Test fun sheetBackgroundLight() = sheet(false, BackgroundVoicesFixtures.sheetBackground, "d112-name-background")

    @Test
    @Config(qualifiers = "+night")
    fun sheetBackgroundDark() = sheet(true, BackgroundVoicesFixtures.sheetBackground, "d112-name-background")

    @Test fun foldedLight() = transcript(false, open = false, "d112-transcript-folded")

    @Test
    @Config(qualifiers = "+night")
    fun foldedDark() = transcript(true, open = false, "d112-transcript-folded")

    @Test fun openedLight() = transcript(false, open = true, "d112-transcript-opened")

    @Test
    @Config(qualifiers = "+night")
    fun openedDark() = transcript(true, open = true, "d112-transcript-opened")

    @Test fun snackbarLight() = snackbar(false, "d112-snackbar")

    @Test
    @Config(qualifiers = "+night")
    fun snackbarDark() = snackbar(true, "d112-snackbar")
}

/** The same states on the unfolded inner display. */
@Config(qualifiers = FOLDABLE_UNFOLDED)
class BackgroundVoicesUnfoldedScreenshotTest : BoardTest() {
    @Test fun sheetLight() = sheet(false, BackgroundVoicesFixtures.sheetBackground, "name-background", UNFOLDED)

    @Test
    @Config(qualifiers = "+night")
    fun sheetDark() = sheet(true, BackgroundVoicesFixtures.sheetBackground, "name-background", UNFOLDED)

    @Test fun openedLight() = transcript(false, open = true, "transcript-opened", UNFOLDED)

    @Test
    @Config(qualifiers = "+night")
    fun openedDark() = transcript(true, open = true, "transcript-opened", UNFOLDED)

    @Test fun snackbarLight() = snackbar(false, "snackbar", UNFOLDED)

    @Test
    @Config(qualifiers = "+night")
    fun snackbarDark() = snackbar(true, "snackbar", UNFOLDED)
}

private const val UNFOLDED = "d112-unfolded"

private fun dir(dark: Boolean, base: String?) = when {
    base == null -> if (dark) "dark" else "light"
    dark -> "$base-dark"
    else -> base
}

private fun BoardTest.sheet(dark: Boolean, ui: NameSheetUi, name: String, base: String? = null) {
    show(dark) {
        NoteScreen(BackgroundVoicesFixtures.transcript.copy(sheet = NoteSheet.NameSpeaker(ui)), onEvent = {})
    }
    rule.mainClock.autoAdvance = true
    rule.onNodeWithText("Save").assertIsDisplayed()
    rule.waitForIdle()
    rule.mainClock.autoAdvance = false
    rule.mainClock.advanceTimeBy(1_000)
    capture(dir(dark, base), name, window = true)
}

private fun BoardTest.transcript(dark: Boolean, open: Boolean, name: String, base: String? = null) {
    show(dark) { NoteScreen(BackgroundVoicesFixtures.transcript, onEvent = {}) }
    rule.onNodeWithText("2 background voices hidden").assertIsDisplayed()
    if (open) {
        rule.onNodeWithTag("background-fold-bg3").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("cappuccinos", substring = true).assertIsDisplayed()
    }
    capture(dir(dark, base), name)
}

private fun BoardTest.snackbar(dark: Boolean, name: String, base: String? = null) {
    show(dark) { NoteScreen(BackgroundVoicesFixtures.snackbar, onEvent = {}) }
    rule.onNodeWithText("Named Marta · 4 background voices").assertIsDisplayed()
    capture(dir(dark, base), name)
}
