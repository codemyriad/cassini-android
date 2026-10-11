package org.cassini.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.cassini.ui.theme.CassiniTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Library excerpt and title never show a space before the "…" (docs/ui-fidelity.md, device follow-up 6). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class EllipsisTextTest {
    @get:Rule val rule = createComposeRule()

    @Test fun anEllipsisNeverComesAfterASpace() {
        var width by mutableIntStateOf(MIN_WIDTH)
        rule.setContent {
            CassiniTheme {
                EllipsisText(
                    text = AnnotatedString(TEXT),
                    style = CassiniTheme.type.snippet,
                    color = CassiniTheme.colors.text3,
                    maxLines = 2,
                    modifier = Modifier
                        .width(width.dp)
                        .testTag(TAG),
                )
            }
        }
        val shown = (MIN_WIDTH..MAX_WIDTH).map { dp ->
            width = dp
            rule.waitForIdle()
            shownText()
        }
        shown.forEach { assertFalse("A space before the ellipsis: \"$it\"", it.endsWith(" …")) }
        val trimmed = shown.filter { it != TEXT }
        assertTrue("No width put the ellipsis after a space; the check found nothing", trimmed.isNotEmpty())
        trimmed.forEach { text ->
            assertTrue(text.endsWith("…"))
            assertTrue(TEXT.startsWith(text.dropLast(1)))
        }
    }

    @Test fun aTextThatFitsStaysTheSame() {
        rule.setContent {
            CassiniTheme {
                EllipsisText(
                    text = AnnotatedString("il cliente"),
                    style = CassiniTheme.type.snippet,
                    color = CassiniTheme.colors.text3,
                    maxLines = 2,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        assertEquals("il cliente", shownText())
    }

    /** The first frame at a width already shows the final text: no frame draws "il …" first. */
    @Test fun theFirstFrameAtAWidthShowsTheFinalText() {
        var width by mutableIntStateOf(MIN_WIDTH)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CassiniTheme {
                EllipsisText(
                    text = AnnotatedString(TEXT),
                    style = CassiniTheme.type.snippet,
                    color = CassiniTheme.colors.text3,
                    maxLines = 2,
                    modifier = Modifier
                        .width(width.dp)
                        .testTag(TAG),
                )
            }
        }
        var trimmed = 0
        for (dp in MIN_WIDTH..MAX_WIDTH) {
            width = dp
            // The width reaches the composition before the frame, so the next frame is the first at this width.
            Snapshot.sendApplyNotifications()
            rule.mainClock.advanceTimeByFrame()
            val first = shownText()
            repeat(SETTLE_FRAMES) { rule.mainClock.advanceTimeByFrame() }
            assertEquals("at $dp dp", shownText(), first)
            if (first != TEXT) trimmed++
        }
        assertTrue("No width cut the text; the check found nothing", trimmed > 0)
    }

    /** The text of the Text inside the tagged EllipsisText. */
    private fun shownText(): String = rule.onNode(hasParent(hasTestTag(TAG))).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    private companion object {
        const val TAG = "ellipsis"
        const val MIN_WIDTH = 120
        const val MAX_WIDTH = 220
        const val SETTLE_FRAMES = 3
        const val TEXT =
            "e quindi il cliente ha detto che la consegna di giovedì va bene se il fornitore conferma entro la " +
                "fine della settimana e se il prezzo resta quello del preventivo di marzo"
    }
}
