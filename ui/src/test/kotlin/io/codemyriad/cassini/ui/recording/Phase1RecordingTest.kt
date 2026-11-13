package io.codemyriad.cassini.ui.recording

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.CassiniTypography
import io.codemyriad.cassini.ui.theme.DarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class Phase1RecordingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun stopIsCentredBetweenAllMeetingsAndPauseAndKeepsTheExistingEvents() {
        val events = mutableListOf<RecordingEvent>()
        show(onEvent = { events += it })
        assertControlTargets()
        rule.onNodeWithText("All meetings").performClick()
        rule.onNodeWithText("Stop").performClick()
        rule.onNodeWithContentDescription("Pause recording").performClick()
        rule.runOnIdle {
            assertEquals(listOf(RecordingEvent.OpenLibrary, RecordingEvent.Finish, RecordingEvent.PauseResume), events)
        }
    }

    @Test fun permissionStartingAndSavingKeepCaptureControlsDisabledAndLibraryAvailable() {
        var phase by mutableStateOf(RecordingPhase.NeedsPermission)
        val events = mutableListOf<RecordingEvent>()
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.ENGLISH) {
                    RecordingScreen(BoardFixtures.recording.copy(phase = phase), onEvent = { events += it })
                }
            }
        }
        for (next in listOf(RecordingPhase.NeedsPermission, RecordingPhase.Starting, RecordingPhase.Stopping)) {
            rule.runOnIdle { phase = next }
            rule.onNodeWithText("Stop").assertIsNotEnabled()
            rule.onNodeWithContentDescription("Pause recording").assertIsNotEnabled()
            rule.onNodeWithText("All meetings").assertIsEnabled().performClick()
        }
        rule.onNodeWithText("Saving the recording…").assertIsDisplayed()
        rule.runOnIdle { assertEquals(List(3) { RecordingEvent.OpenLibrary }, events) }
    }

    @Test fun pausedRecordingShowsTheLabelledResumeAndRetainsStop() {
        val events = mutableListOf<RecordingEvent>()
        show(phase = RecordingPhase.Paused, onEvent = { events += it })
        rule.onNodeWithText("Resume").assertIsDisplayed()
        rule.onNodeWithContentDescription("Resume recording").performClick()
        rule.onNodeWithText("Stop").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(listOf(RecordingEvent.PauseResume, RecordingEvent.Finish), events) }
    }

    @Test fun theTitleKeepsItsEditableSpokenLabelWithoutAVisibleTitleCaption() {
        val events = mutableListOf<RecordingEvent>()
        show(onEvent = { events += it })
        rule.onNodeWithText("Title").assertDoesNotExist()
        rule.onNodeWithContentDescription("Title").assert(hasSetTextAction()).performTextReplacement("Renamed meeting")
        rule.runOnIdle { assertEquals(listOf(RecordingEvent.Title("Renamed meeting")), events) }
        rule.onNodeWithText("Opus 48 kHz").assertIsDisplayed()
    }

    @Test fun lightAppThemeStillRecordsOnNightGround() = nightGround(dark = false)

    @Test fun darkAppThemeRecordsOnTheSameNightGround() = nightGround(dark = true)

    @Test
    @Config(qualifiers = "en-rUS-w780dp-h360dp-xhdpi")
    fun shortWindowsKeepCaptureControlsVisibleWhileTheMeterAreaScrolls() {
        show()
        assertControlTargets()
        rule.onNodeWithContentDescription("Title").assertIsDisplayed()
    }

    @Test fun largeTextKeepsSeparateControlTargets() {
        show(fontScale = 2f)
        assertControlTargets()
    }

    @Test fun theRecordingTimerUsesTheApprovedSystemTabularType() {
        assertEquals(56.sp, CassiniTypography.timer.fontSize)
        assertEquals(FontWeight.Medium, CassiniTypography.timer.fontWeight)
        assertEquals("tnum", CassiniTypography.timer.fontFeatureSettings)
    }

    private fun nightGround(dark: Boolean) {
        show(dark = dark, phase = RecordingPhase.Paused)
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(DarkColors.bg, pixels[0, 0])
    }

    private fun assertControlTargets() {
        val library = rule.onNodeWithText("All meetings").assertIsDisplayed().getBoundsInRoot()
        val stop = rule.onNodeWithText("Stop").assertIsDisplayed().getBoundsInRoot()
        val pause = rule.onNodeWithContentDescription("Pause recording").assertIsDisplayed().getBoundsInRoot()
        assertTrue(library.right <= stop.left)
        assertTrue(stop.right <= pause.left)
        val window = rule.onRoot().getBoundsInRoot()
        assertEquals((window.left.value + window.right.value) / 2, (stop.left.value + stop.right.value) / 2, 1f)
        for (bounds in listOf(library, stop, pause)) {
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
    }

    private fun show(
        dark: Boolean = false,
        phase: RecordingPhase = RecordingPhase.Recording,
        fontScale: Float = 1f,
        onEvent: (RecordingEvent) -> Unit = {},
    ) {
        rule.setContent {
            CassiniTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    ProvideFixedFormat(locale = Locale.ENGLISH) {
                        RecordingScreen(BoardFixtures.recording.copy(phase = phase), onEvent = onEvent)
                    }
                }
            }
        }
    }
}
