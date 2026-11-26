package io.codemyriad.cassini.ui.settings

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** D-1178: Experimental features, unlocked by eight taps on the version in About, and their Voice recorder switch. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class ExperimentalSettingsTest {
    @get:Rule val rule = createComposeRule()

    private val recorderRow = SettingsExtraRow("Recorder", "Not bound")

    @Test fun theTapsCountDownFromTheFifthAndTheEighthUnlocks() {
        assertEquals(1 to ExperimentalTaps.Feedback.None, ExperimentalTaps.tap(0, unlocked = false))
        assertEquals(4 to ExperimentalTaps.Feedback.None, ExperimentalTaps.tap(3, unlocked = false))
        assertEquals(5 to ExperimentalTaps.Feedback.TapsAway(3), ExperimentalTaps.tap(4, unlocked = false))
        assertEquals(7 to ExperimentalTaps.Feedback.TapsAway(1), ExperimentalTaps.tap(6, unlocked = false))
        assertEquals(0 to ExperimentalTaps.Feedback.Unlocked, ExperimentalTaps.tap(7, unlocked = false))
        assertEquals(0 to ExperimentalTaps.Feedback.AlreadyUnlocked, ExperimentalTaps.tap(7, unlocked = true))
    }

    @Test fun aLockedMapShowsNeitherExperimentalFeaturesNorTheRecorder() {
        rule.setContent {
            CassiniTheme {
                SettingsScreen(BoardFixtures.settings.copy(voiceRecorderEnabled = true), {}, extraRow = recorderRow)
            }
        }
        rule.onNodeWithText("Language").assertExists()
        rule.onNodeWithText("Experimental features").assertDoesNotExist()
        rule.onNodeWithText("Recorder").assertDoesNotExist()
    }

    @Test fun eightTapsOnTheVersionCountDownAndUnlock() {
        val events = mutableListOf<SettingsEvent>()
        about(events)
        val version = rule.onNodeWithText("Version 1.2.3")
        repeat(4) { version.tap() }
        rule.onNodeWithText("taps away from Experimental features", substring = true).assertDoesNotExist()
        version.tap()
        rule.onNodeWithText("3 taps away from Experimental features").assertExists()
        version.tap()
        rule.onNodeWithText("2 taps away from Experimental features").assertExists()
        version.tap()
        rule.onNodeWithText("1 tap away from Experimental features").assertExists()
        assertEquals(emptyList<SettingsEvent>(), events)
        version.tap()
        rule.onNodeWithText("Experimental features unlocked").assertExists()
        assertEquals(listOf<SettingsEvent>(SettingsEvent.UnlockExperimental), events)
    }

    @Test fun threeSecondsWithoutATapStartTheCountAgain() {
        val events = mutableListOf<SettingsEvent>()
        about(events)
        val version = rule.onNodeWithText("Version 1.2.3")
        repeat(5) { version.tap() }
        rule.onNodeWithText("3 taps away from Experimental features").assertExists()
        rule.mainClock.advanceTimeBy(ExperimentalTaps.TAP_RESET_MS + 100)
        rule.onNodeWithText("3 taps away from Experimental features").assertDoesNotExist()
        repeat(7) { version.tap() }
        rule.onNodeWithText("1 tap away from Experimental features").assertExists()
        assertEquals(emptyList<SettingsEvent>(), events)
    }

    @Test fun tapsAfterTheUnlockNeverLockAgain() {
        val events = mutableListOf<SettingsEvent>()
        about(events, unlocked = true)
        val version = rule.onNodeWithText("Version 1.2.3")
        repeat(9) { version.tap() }
        rule.onNodeWithText("Experimental features are already unlocked").assertExists()
        assertEquals(emptyList<SettingsEvent>(), events)
    }

    @Test fun theExperimentalPageTurnsTheRecorderOnAndHidesItselfWhenTurnedOff() {
        val events = mutableListOf<SettingsEvent>()
        val state = mutableStateOf(BoardFixtures.settings.copy(experimentalUnlocked = true))
        var recorderOpened = 0
        lateinit var back: OnBackPressedDispatcher
        rule.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CassiniTheme {
                SettingsScreen(state.value, events::add, extraRow = recorderRow, onExtraRow = { recorderOpened++ })
            }
        }
        rule.onNodeWithText("Early previews that may change or go away").performScrollTo().performClick()
        rule.onNodeWithText("These features are early previews", substring = true).assertExists()
        rule.onNodeWithText("Voice recorder").assertIsOff().performClick()
        rule.onNodeWithText("Recorder").assertDoesNotExist()
        rule.runOnIdle { state.value = state.value.copy(voiceRecorderEnabled = true) }
        rule.onNodeWithText("Voice recorder").assertIsOn()
        rule.onNodeWithText("Recorder").performClick()
        rule.onNodeWithText("Turn off to hide this page", substring = true).assertIsOn().performClick()
        rule.runOnIdle {
            assertEquals(1, recorderOpened)
            assertEquals(
                listOf(SettingsEvent.VoiceRecorder(true), SettingsEvent.ExperimentalFeatures(false)),
                events,
            )
            state.value = state.value.copy(experimentalUnlocked = false, voiceRecorderEnabled = false)
        }
        // The page stays until it is left, so the switch can go back on; then the map hides it.
        rule.onNodeWithText("Turn off to hide this page", substring = true).assertIsOff()
        rule.onNodeWithText("Recorder").assertDoesNotExist()
        rule.runOnIdle { back.onBackPressed() }
        rule.onNodeWithText("Language").assertExists()
        rule.onNodeWithText("Experimental features").assertDoesNotExist()
    }

    private fun about(events: MutableList<SettingsEvent>, unlocked: Boolean = false) {
        rule.setContent {
            CassiniTheme {
                SettingsScreen(
                    BoardFixtures.settings.copy(version = "1.2.3", experimentalUnlocked = unlocked),
                    events::add,
                    initialPage = SettingsPage.ABOUT,
                )
            }
        }
        rule.mainClock.autoAdvance = false
    }

    /** One tap, then a frame: the clock moves only as far as the taps of a quick streak. */
    private fun SemanticsNodeInteraction.tap() {
        performClick()
        rule.mainClock.advanceTimeBy(TAP_GAP_MS)
    }

    private companion object {
        const val TAP_GAP_MS = 100L
    }
}
