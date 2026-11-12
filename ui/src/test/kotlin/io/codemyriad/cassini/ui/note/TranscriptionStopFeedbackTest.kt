package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
        val stop = rule.onNodeWithText("Stop and keep progress")
        stop.assertIsEnabled().performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(NoteEvent.Stop), events) }
        rule.runOnIdle { state.value = initial.copy(mode = mode.copy(stopBusy = true)) }
        rule.waitForIdle()
        stop.assertIsNotEnabled().performTouchInput { click() }
        rule.runOnIdle { assertEquals("checkpointing must not accept another Stop", listOf(NoteEvent.Stop), events) }
        rule.runOnIdle { state.value = initial }
        rule.waitForIdle()
        stop.assertIsEnabled().performTouchInput { click() }
        rule.runOnIdle { assertEquals(listOf(NoteEvent.Stop, NoteEvent.Stop), events) }
    }
}
