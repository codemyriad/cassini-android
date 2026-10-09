package org.cassini.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProgressHeadline
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.format.StageState
import org.cassini.ui.format.StageUi
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Semantics of the batch-2 controls (docs/design/ui.md sections 5, 6 and 11): the stepper's state descriptions, the
 * progress card's throttled live region, the snackbar's live region, custom action and timeout, the checkbox row,
 * the text field's label and error, the Recording widgets, sheet rows, menu items and dialog titles.
 *
 * Accessibility Test Framework checks do not run under Robolectric (see TranscriptSemanticsTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class ControlsSemanticsTest {
    @get:Rule val rule = createComposeRule()

    private fun setThemed(content: @Composable () -> Unit) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE, content = content)
            }
        }
    }

    @Test fun eachStepHasItsPositionAndState() {
        setThemed { StageTracker(StageUi.SAVING, skippedMatching = true) }
        val expected = listOf(
            "Step 1 of 5: Reading" to "done",
            "Step 2 of 5: Preparing" to "done",
            "Step 3 of 5: Words and voices" to "done",
            "Step 4 of 5: Matching" to "skipped",
            "Step 5 of 5: Saving" to "in progress",
        )
        for ((description, state) in expected) {
            rule.onNodeWithContentDescription(description)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
        }
        // Matching is skipped wherever the job is, and in progress only if it runs after all.
        assertEquals(
            listOf(
                StageState.IN_PROGRESS,
                StageState.NOT_STARTED,
                StageState.NOT_STARTED,
                StageState.SKIPPED,
                StageState.NOT_STARTED,
            ),
            stageStates(StageUi.READING, skippedMatching = true),
        )
        assertEquals(StageState.IN_PROGRESS, stageStates(StageUi.MATCHING, skippedMatching = true)[3])
    }

    @Test fun theProgressCardAnnouncesStagesAndFivePercentSteps() {
        var card by mutableStateOf(TranscriptFixtures.boardCard)
        setThemed { ProcessingCard(card, onStop = {}) }
        val first = "Finding words and speakers, 60%"
        rule.onNode(hasContentDescription(first))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.runOnIdle { card = card.copy(percent = 64) }
        rule.onNode(hasContentDescription(first)).assertExists()
        rule.runOnIdle { card = card.copy(percent = 65) }
        rule.onNode(hasContentDescription("Finding words and speakers, 65%")).assertExists()
        rule.runOnIdle { card = card.copy(headline = ProgressHeadline.SAVING) }
        rule.onNode(hasContentDescription("Saving, 65%")).assertExists()
        rule.onNodeWithText("Stop and keep progress").assertTouchAtLeast48()
    }

    @Test fun theSnackbarIsALiveRegionWithAnUndoActionAndATimeout() {
        var undo = 0
        var timeouts = 0
        rule.mainClock.autoAdvance = false
        setThemed {
            CassiniSnackbarHost(
                message = "“Call with supplier” moved to trash",
                durationMs = TIMEOUT_MS,
                onTimeout = { timeouts++ },
                actionLabel = "Undo",
                onAction = { undo++ },
            )
        }
        rule.mainClock.advanceTimeByFrame()
        val bar = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val custom = bar.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Undo"), custom.map { it.label })
        rule.runOnIdle { custom.single().action() }
        rule.onNodeWithText("Undo").assertTouchAtLeast48().performClick()
        rule.mainClock.advanceTimeBy(TIMEOUT_MS - ONE_SECOND_MS)
        rule.runOnIdle { assertEquals(0, timeouts) }
        rule.mainClock.advanceTimeBy(2 * ONE_SECOND_MS)
        rule.runOnIdle {
            assertEquals(2, undo)
            assertEquals(1, timeouts)
        }
    }

    @Test fun theCheckboxRowIsOneToggle() {
        var checked by mutableStateOf(true)
        setThemed {
            CassiniCheckboxRow(
                checked = checked,
                onCheckedChange = { checked = it },
                title = "Remember this voice on this phone",
                subtitle = "The voiceprint never leaves the phone.",
            )
        }
        val row = rule.onNodeWithText("Remember this voice on this phone", substring = true)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .assertTouchAtLeast48()
            .performClick()
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
    }

    @Test fun theTextFieldCarriesItsLabelAndError() {
        setThemed {
            Column {
                CassiniTextField("Anna", onValueChange = {}, label = "Name")
                CassiniTextField("", onValueChange = {}, label = "Title", error = "Enter a title.")
            }
        }
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and hasText("Name"))
            .assertExists()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Enter a title.")).assertExists()
    }

    @Test fun theRecordingWidgetsSpeakTheirState() {
        var paused by mutableStateOf(false)
        var pauses by mutableIntStateOf(0)
        setThemed {
            Column {
                RecordingStatus(paused = paused)
                RecordingTimer(1_447_000)
                Waveform(boardWaveformLevels())
                RecordingControls(paused = paused, onLibrary = {}, onPauseResume = { pauses++ }, onFinish = {})
            }
        }
        rule.onNodeWithText("Recording")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val timer = rule.onNodeWithContentDescription("24 minutes 7 seconds")
        timer.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        rule.onNodeWithContentDescription("Microphone level, last 7 seconds")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
        rule.onNodeWithText("Library").assertHasClickAction().assertTouchAtLeast48()
        rule.onNodeWithText("Finish").assertHasClickAction().assertTouchAtLeast48()
        rule.onNodeWithContentDescription("Pause recording").assertTouchAtLeast48().performClick()
        rule.runOnIdle {
            assertEquals(1, pauses)
            paused = true
        }
        rule.onNodeWithText("Paused").assertExists()
        rule.onNodeWithContentDescription("Resume recording").assertExists()
    }

    @Test fun sheetRowsMenuItemsAndDialogsAreLabelledControls() {
        var clicks = 0
        setThemed {
            Column(Modifier.width(390.dp)) {
                SheetSection("Save a copy")
                SheetActionRow("Rename", Glyph.Pencil, onClick = { clicks++ })
                SheetActionRow(
                    label = "Subtitles",
                    glyph = Glyph.Subtitles,
                    onClick = { clicks++ },
                    subtitle = "Available after transcription",
                    enabled = false,
                )
                CassiniMenuContent { CassiniMenuItem("Forget", onClick = { clicks++ }, destructive = true) }
                DeleteDialogContent()
            }
        }
        rule.onNodeWithText("SAVE A COPY").assert(isHeading())
        rule.onNodeWithText("Rename").assertTouchAtLeast48().performClick()
        rule.onNodeWithText("Subtitles", substring = true).performClick()
        rule.onNodeWithText("Forget").assertTouchAtLeast48().performClick()
        rule.onNodeWithText("Delete for good?").assert(isHeading())
        rule.onNodeWithText("Cancel").assertTouchAtLeast48()
        rule.onNodeWithText("Delete").assertTouchAtLeast48()
        rule.runOnIdle { assertEquals(2, clicks) }
        rule.onAllNodesWithContentDescription("Pencil").assertCountEquals(0)
    }

    private fun SemanticsNodeInteraction.assertTouchAtLeast48(): SemanticsNodeInteraction {
        val node = fetchSemanticsNode()
        val touch = node.touchBoundsInRoot
        val min = with(rule.density) { 48.dp.toPx() } - PX_TOLERANCE
        assertTrue("${node.config} touch width ${touch.width} px", touch.width >= min)
        assertTrue("${node.config} touch height ${touch.height} px", touch.height >= min)
        return this
    }

    private companion object {
        const val PX_TOLERANCE = 0.5f
        const val TIMEOUT_MS = 10_000L
        const val ONE_SECOND_MS = 1_000L
    }
}
