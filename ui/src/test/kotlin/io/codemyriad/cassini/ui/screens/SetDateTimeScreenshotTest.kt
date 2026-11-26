// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package io.codemyriad.cassini.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.components.LocalDetailPane
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.library.DaySection
import io.codemyriad.cassini.ui.library.LibraryScreen
import io.codemyriad.cassini.ui.note.NoteDialog
import io.codemyriad.cassini.ui.note.NoteScreen
import io.codemyriad.cassini.ui.note.NoteSheet
import io.codemyriad.cassini.ui.note.SetDateTimeUi
import io.codemyriad.cassini.ui.note.StartDatePicker
import io.codemyriad.cassini.ui.note.StartPicker
import io.codemyriad.cassini.ui.note.StartTimePicker
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.LocalTime

/**
 * "Set date and time" (D112, L-87, D-1164) as its windows show it: the actions sheet with the new row under Star,
 * "When was this recorded?" (with and without the saved-time line), the Material 3 date and time pickers above it,
 * and the sheet beside the Library on an unfolded foldable, rising from the bottom of the right pane.
 */
class SetDateTimeScreenshotTest : BoardTest() {
    // Yesterday relative to BoardTest.FIXED_NOW (2026-10-09). Must stay in a past month (D88): Material 3's
    // DatePicker outlines LocalDate.now() whenever the displayed month matches the host wall clock.
    private val prefill = SetDateTimeUi("n", FIXED_NOW.toLocalDate().minusDays(1), LocalTime.of(14, 36))
    private val saved = prefill.copy(fromFileSaved = true)

    @Test fun actionsLight() = actions(dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun actionsDark() = actions(dark = true)

    @Test fun sheetLight() = sheet(dark = false, ui = saved, name = "set-date-time-saved-hint")

    @Test
    @Config(qualifiers = "+night")
    fun sheetDark() = sheet(dark = true, ui = saved, name = "set-date-time-saved-hint")

    @Test fun sheetWithAStartLight() = sheet(dark = false, ui = prefill, name = "set-date-time")

    @Test
    @Config(qualifiers = "+night")
    fun sheetWithAStartDark() = sheet(dark = true, ui = prefill, name = "set-date-time")

    @Test fun dateRowOpensTheDatePicker() = opens("start-date")

    @Test fun timeRowOpensTheTimePicker() = opens("start-time")

    @Test fun datePickerLight() = picker(dark = false, kind = StartPicker.DATE)

    @Test
    @Config(qualifiers = "+night")
    fun datePickerDark() = picker(dark = true, kind = StartPicker.DATE)

    @Test fun timePickerLight() = picker(dark = false, kind = StartPicker.TIME)

    @Test
    @Config(qualifiers = "+night")
    fun timePickerDark() = picker(dark = true, kind = StartPicker.TIME)

    @Test
    @Config(qualifiers = FormFactorBoardTest.FOLDABLE_UNFOLDED)
    fun unfoldedLight() = unfolded(dark = false)

    @Test
    @Config(qualifiers = "w673dp-h841dp-port-night-xhdpi")
    fun unfoldedDark() = unfolded(dark = true)

    @Test fun libraryBeforeLight() = library(dark = false, saved = false)

    @Test fun libraryAfterLight() = library(dark = false, saved = true)

    @Test
    @Config(qualifiers = "+night")
    fun libraryBeforeDark() = library(dark = true, saved = false)

    @Test
    @Config(qualifiers = "+night")
    fun libraryAfterDark() = library(dark = true, saved = true)

    /**
     * The Library before Save, with the imported clip under "Date unknown", and after Save at 14:36 yesterday: the
     * clip joins its day in time order and "Date unknown" is gone. (The order itself is `LibraryMapping`'s, tested in
     * `:app`; this is what the person sees.)
     */
    private fun library(dark: Boolean, saved: Boolean) {
        val f = BoardFixtures
        val clip = f.voiceNote.copy(id = "rec001", title = "REC001", durationMs = 1_312_000, imported = true)
        val sections = if (saved) {
            persistentListOf(
                DaySection(DayLabel.Today, persistentListOf(f.weeklySync, f.interview)),
                DaySection(
                    DayLabel.Yesterday,
                    persistentListOf(f.supplier, clip.copy(time = LocalTime.of(14, 36)), f.boardReview)
                ),
            )
        } else {
            persistentListOf(
                DaySection(DayLabel.Today, persistentListOf(f.weeklySync, f.interview)),
                DaySection(DayLabel.Yesterday, persistentListOf(f.supplier, f.boardReview)),
                DaySection(DayLabel.Unknown, persistentListOf(clip.copy(time = LocalTime.MIDNIGHT))),
            )
        }
        show(dark) { LibraryScreen(f.library.copy(progress = null, sections = sections), onEvent = {}) }
        settle()
        assertEquals(
            !saved,
            rule.onAllNodes(
                androidx.compose.ui.test.hasText("DATE UNKNOWN", ignoreCase = true)
            ).fetchSemanticsNodes().isNotEmpty()
        )
        capture(
            if (dark) "dark" else "light",
            if (saved) "set-date-time-library-after" else "set-date-time-library-before"
        )
    }

    private fun settle() {
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
    }

    private fun actions(dark: Boolean) {
        show(
            dark
        ) { NoteScreen(BoardFixtures.readAndListen.copy(sheet = NoteSheet.Actions(BoardFixtures.noteActions)), {}) }
        settle()
        rule.onNodeWithTag("actions-set-date-time").assertIsDisplayed()
        capture(if (dark) "dark" else "light", "set-date-time-actions", window = true)
    }

    /** Tapping a row of the sheet opens its Material 3 picker above it, with OK and Cancel. */
    private fun opens(row: String) {
        show { NoteScreen(BoardFixtures.readAndListen.copy(dialog = NoteDialog.SetDateTime(prefill)), {}) }
        settle()
        rule.onNodeWithTag(row).performClick()
        settle()
        rule.onNodeWithText("OK").assertIsDisplayed()
    }

    /**
     * The picker as its dialog window shows it. Over the meeting, not over the sheet: two stacked scrims would make the
     * light capture read as dark in the screenshot contract (`tests/pr_shots`).
     */
    private fun picker(dark: Boolean, kind: StartPicker) {
        show(dark) {
            NoteScreen(BoardFixtures.readAndListen, {})
            when (kind) {
                StartPicker.DATE -> StartDatePicker(prefill.date, onPick = {}, onDismiss = {})
                StartPicker.TIME -> StartTimePicker(prefill.time, onPick = {}, onDismiss = {})
            }
        }
        settle()
        val name = if (kind == StartPicker.DATE) "set-date-time-date-picker" else "set-date-time-time-picker"
        capture(if (dark) "dark" else "light", name, window = true)
    }

    private fun sheet(dark: Boolean, ui: SetDateTimeUi, name: String, open: String? = null) {
        show(dark) { NoteScreen(BoardFixtures.readAndListen.copy(dialog = NoteDialog.SetDateTime(ui)), {}) }
        settle()
        rule.onNodeWithText("When was this recorded?").assertIsDisplayed()
        rule.onNodeWithText("8 Oct 2026").assertIsDisplayed()
        rule.onNodeWithText("14:36").assertIsDisplayed()
        val hint = "Filled in from when the file was saved. Change it if it’s wrong."
        assertEquals(
            ui.fromFileSaved,
            rule.onAllNodes(androidx.compose.ui.test.hasText(hint)).fetchSemanticsNodes().isNotEmpty()
        )
        if (open != null) {
            rule.onNodeWithTag(open).performClick()
            settle()
        }
        capture(if (dark) "dark" else "light", name, window = true)
    }

    private fun unfolded(dark: Boolean) {
        show(
            dark
        ) { SideBySide { NoteScreen(BoardFixtures.readAndListen.copy(dialog = NoteDialog.SetDateTime(saved)), {}) } }
        settle()
        rule.onNodeWithText("When was this recorded?").assertIsDisplayed()
        capture(if (dark) "foldable-unfolded-dark" else "foldable-unfolded", "set-date-time", window = true)
    }

    /** The app's list-detail scene on a 673 dp window: the Library in 280 dp, a 1 dp line, the meeting beside it. */
    @Composable
    private fun SideBySide(detail: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDetailPane provides PaddingValues(start = LIST_WIDTH + 1.dp)) {
            Row {
                Box(Modifier.width(LIST_WIDTH).fillMaxHeight()) { BoardContent("00-library") }
                VerticalDivider(thickness = 1.dp, color = CassiniTheme.colors.line)
                Box(Modifier.fillMaxHeight()) { detail() }
            }
        }
    }

    private companion object {
        val LIST_WIDTH = 280.dp
    }
}
