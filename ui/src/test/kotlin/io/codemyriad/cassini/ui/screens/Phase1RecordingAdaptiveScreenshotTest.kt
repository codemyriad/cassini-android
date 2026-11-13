package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.fixtures.VariantFixtures
import io.codemyriad.cassini.ui.recording.RecordingScreen
import org.junit.Test
import org.robolectric.annotation.Config

/** Linux evidence for reachable alerts and feedback with pinned controls in a short, large-text viewport. */
@Config(qualifiers = "w390dp-h360dp-xhdpi", fontScale = 2f)
class Phase1RecordingAdaptiveScreenshotTest : BoardTest() {
    override val fontScale = 2f

    @Test fun bannerAndSavingFeedbackLight() = adaptive(dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun bannerAndSavingFeedbackDark() = adaptive(dark = true)

    private fun adaptive(dark: Boolean) {
        show(dark) {
            RecordingScreen(VariantFixtures.recording.getValue("recording-stopped-file-limit"), onEvent = {})
        }
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Open note").performScrollTo()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        val directory = if (dark) "adaptive-dark" else "adaptive"
        capture(directory, "recording-short-large-banner")
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Saving the recording…").performScrollTo()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        capture(directory, "recording-short-large-feedback")
    }
}
