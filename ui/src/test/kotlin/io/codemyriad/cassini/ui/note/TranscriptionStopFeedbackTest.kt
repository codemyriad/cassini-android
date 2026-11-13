package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.fixtures.VariantFixtures
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class TranscriptionStopFeedbackTest {
    @get:Rule val rule = createComposeRule()

    @Test fun stoppingDisablesRepeatStopAndTheNextRunEnablesItAgain() {
        val initial = BoardFixtures.transcribing
        val mode = initial.mode as NoteMode.Processing
        val state = mutableStateOf(initial)
        val events = mutableListOf<NoteEvent>()
        rule.setContent { CassiniTheme { NoteScreen(state.value, onEvent = events::add) } }
        rule.waitForIdle()
        val stop = rule.onNodeWithContentDescription("Stop and keep progress")
        stop.assertIsEnabled().performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(NoteEvent.Stop), events) }
        rule.runOnIdle { state.value = initial.copy(mode = mode.copy(stopBusy = true)) }
        rule.waitForIdle()
        stop.assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Saving…"))
            .performTouchInput { click() }
        rule.runOnIdle { assertEquals("checkpointing must not accept another Stop", listOf(NoteEvent.Stop), events) }
        rule.runOnIdle { state.value = initial }
        rule.waitForIdle()
        stop.assertIsEnabled()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(NoteEvent.Stop, NoteEvent.Stop), events) }
    }

    @Test fun detailsStopControlDisablesAndReportsSavingWhileCheckpointing() {
        val card = BoardFixtures.transcribingCard
        val busy = mutableStateOf(false)
        var stops = 0
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.ENGLISH) {
                    TranscribingDetailsContent(card, busy.value, false, { stops++ }, {})
                }
            }
        }
        val stop = rule.onNodeWithTag("transcribing-details-stop")
        stop.performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(1, stops)
            busy.value = true
        }
        stop.assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Saving…"))
            .performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun actionsSheetStopRowDisablesAndReportsSavingWhileCheckpointing() {
        val plain = VariantFixtures.noteActions.getValue("actions-plain")
        val baseActions = plain.copy(transcribeRow = TranscribeRowUi.Stop)
        val busy = mutableStateOf(false)
        val actions = mutableListOf<NoteAction>()
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.ENGLISH) {
                    NoteActionsSheetContent(
                        baseActions.copy(busyRow = NoteAction.STOP.takeIf { busy.value }),
                        onAction = actions::add,
                    )
                }
            }
        }
        val stop = rule.onNode(hasText("Stop and keep progress").and(hasClickAction()))
        stop.performScrollTo().assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals(listOf(NoteAction.STOP), actions)
            busy.value = true
        }
        stop.assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Saving…"))
            .performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(NoteAction.STOP), actions) }
    }
}
