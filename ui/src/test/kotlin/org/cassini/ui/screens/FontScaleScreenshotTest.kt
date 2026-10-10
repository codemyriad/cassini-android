package org.cassini.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.cassini.ui.BoardTest
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Four boards at font scale 1.5 (docs/design/ui.md section 10.2), goldens only in `fontscale/`. Rows grow with the
 * text and the screens scroll; no text is cut. The Name sheet and Settings scroll past the screen, so each has a
 * second golden (`-end`) scrolled to its last part.
 */
@Config(fontScale = 1.5f)
class FontScaleScreenshotTest : BoardTest() {
    override val fontScale: Float = 1.5f

    private fun large(slug: String) = board("fontscale", slug) { BoardContent(slug) }

    /** Draws [slug], scrolls until the node with [text] is in view, and captures `<slug>-end`. */
    private fun largeEnd(slug: String, text: String) {
        show { BoardContent(slug) }
        // performScrollTo waits for the scroll to settle, which needs the clock to run.
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText(text, substring = true).performScrollTo()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        capture("fontscale", "$slug-end")
    }

    @Test fun library() = large("00-library")

    @Test fun readAndListen() = large("03-read-and-listen")

    @Test fun nameSpeaker() = large("04-name-a-speaker")

    @Test fun nameSpeakerEnd() = largeEnd("04-name-a-speaker", "Save name")

    @Test fun settings() = large("08-settings")

    @Test fun settingsEnd() = largeEnd("08-settings", "Free software")

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
