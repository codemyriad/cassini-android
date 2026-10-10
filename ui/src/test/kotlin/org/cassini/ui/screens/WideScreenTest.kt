package org.cassini.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.dp
import org.cassini.ui.BoardTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * On a window wider than 560 dp (a tablet, a phone in landscape), each screen limits its content to 560 dp and
 * centres it, while its background fills the window (architecture.md section 6). Three goldens in `wide/` show it;
 * the other tests check that every text of each board screen is inside the centred 560 dp band.
 */
@Config(qualifiers = WideScreenTest.WIDE)
class WideScreenTest : BoardTest() {
    /** Checks that each text node of [slug] lies inside the centred band. */
    private fun textsAreCentred(slug: String) {
        show { BoardContent(slug) }
        val left = (WIDTH_DP - MAX_DP) / 2
        val right = left + MAX_DP
        val texts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
        assertTrue("$slug shows no text", texts.isNotEmpty())
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).apply {
            for (i in texts.indices) {
                val bounds = get(i).getBoundsInRoot()
                if (bounds.right <= bounds.left) continue
                assertTrue("$slug: a text starts at ${bounds.left}", bounds.left.value >= left - TOLERANCE)
                assertTrue("$slug: a text ends at ${bounds.right}", bounds.right.value <= right + TOLERANCE)
            }
        }
    }

    @Test fun libraryGolden() = board("wide", "00-library") { BoardContent("00-library") }

    @Test fun recordingGolden() = board("wide", "01-recording") { BoardContent("01-recording") }

    @Test fun readAndListenGolden() = board("wide", "03-read-and-listen") { BoardContent("03-read-and-listen") }

    @Test fun library() = textsAreCentred("00-library")

    @Test fun recording() = textsAreCentred("01-recording")

    @Test fun transcribing() = textsAreCentred("02-transcribing")

    @Test fun readAndListen() = textsAreCentred("03-read-and-listen")

    @Test fun modelDownload() = textsAreCentred("05-model-download")

    @Test fun savedVoices() = textsAreCentred("07-saved-voices")

    @Test fun settings() = textsAreCentred("08-settings")

    @Test fun trash() = textsAreCentred("09-trash")

    companion object {
        /** A window wider than the 560 dp limit, at the boards' density. */
        const val WIDE = "w900dp-h700dp-xhdpi"
        private const val WIDTH_DP = 900f
        private val MAX_DP = 560.dp.value
        private const val TOLERANCE = 0.5f
    }
}
