package org.cassini.ui.scaffold

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The P2 proof that Roborazzi with Robolectric native graphics works in this AGP 9 library module, and that a
 * transcript paragraph can be one Text whose words are LinkAnnotation.Clickable spans with the active word
 * highlighted behind the glyphs (docs/design/ui.md, section 10.1). It stays as the smallest check of that set-up:
 * when a Roborazzi, Robolectric or AGP update breaks capture, this test fails first and is easy to read. The
 * screens have their own goldens; these live in src/test/screenshots/scaffold.
 *
 * ui.md section 10.1 also asks for one enableAccessibilityChecks() test here. It cannot run: under Robolectric the
 * Compose rule logs "Accessibility checks are currently not supported by Robolectric" and checks nothing. The
 * fallback in features.md section 14 applies: the P10 device flow tests run the checks on an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class ScaffoldScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun hello() {
        rule.setContent {
            Box(Modifier.background(Color.White).padding(16.dp)) {
                Text("Hello, Cassini", color = Color(0xFF1B1F24), fontSize = 20.sp)
            }
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/scaffold/hello.png")
    }

    @Test fun linkParagraph() {
        var clicked = -1
        rule.setContent {
            Box(Modifier.background(Color.White).padding(16.dp)) {
                WordParagraph(words = WORDS, active = ACTIVE_WORD, onWord = { clicked = it })
            }
        }
        rule.onRoot().captureRoboImage("src/test/screenshots/scaffold/link-paragraph.png")

        // A tap on a word reaches that word's LinkAnnotation.Clickable.
        rule.onNodeWithTag(PARAGRAPH_TAG).performTouchInput { click(lastLayout.wordCentre(TAPPED_WORD)) }
        rule.runOnIdle { assertEquals(TAPPED_WORD, clicked) }
    }

    private lateinit var lastLayout: ParagraphLayout

    /** A paragraph of clickable words, with a rounded rectangle behind the [active] word. */
    @Composable
    private fun WordParagraph(words: List<String>, active: Int, onWord: (Int) -> Unit) {
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val ranges = ArrayList<IntRange>(words.size)
        val text = buildAnnotatedString {
            words.forEachIndexed { index, word ->
                if (index > 0) append(' ')
                val start = length
                val link = LinkAnnotation.Clickable(
                    tag = "word-$index",
                    styles = TextLinkStyles(style = SpanStyle(color = INK)),
                    linkInteractionListener = { onWord(index) },
                )
                withLink(link) { append(word) }
                ranges += start until length
            }
        }
        Text(
            text = text,
            style = TextStyle(fontSize = 18.sp, lineHeight = 28.sp),
            onTextLayout = {
                layout = it
                lastLayout = ParagraphLayout(it, ranges)
            },
            modifier = Modifier
                .width(358.dp)
                .testTag(PARAGRAPH_TAG)
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    val range = ranges[active]
                    val bounds = result.getPathForRange(range.first, range.last + 1).getBounds()
                    val inset = Offset(3.dp.toPx(), 1.dp.toPx())
                    drawRoundRect(
                        color = ACTIVE_FILL,
                        topLeft = bounds.topLeft - inset,
                        size = Size(bounds.width + 2 * inset.x, bounds.height + 2 * inset.y),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                },
        )
    }

    private class ParagraphLayout(val result: TextLayoutResult, val ranges: List<IntRange>) {
        fun wordCentre(index: Int): Offset {
            val range = ranges[index]
            return result.getPathForRange(range.first, range.last + 1).getBounds().center
        }
    }

    private companion object {
        const val PARAGRAPH_TAG = "paragraph"
        const val ACTIVE_WORD = 7
        const val TAPPED_WORD = 12
        val INK = Color(0xFF1B1F24)
        val ACTIVE_FILL = Color(0xFFD6E4F5)
        val WORDS = (
            "Then we agreed that the delivery moves to Thursday, but the price stays as agreed in the March order, " +
                "and the supplier sends the revised schedule before the weekly product sync."
            ).split(' ')
    }
}
