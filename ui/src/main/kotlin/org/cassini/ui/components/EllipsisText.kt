// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * A [Text] of at most [maxLines] lines that ends with "…" when it is cut, with no space before the "…".
 * `TextOverflow.Ellipsis` cuts at a character and can keep the space before the cut ("il …"); the boards never show
 * that space. Before the text is drawn, a [rememberTextMeasurer] layout at the given width finds the cut. When it
 * puts the ellipsis after white space, the text is drawn as the part before the cut, without its trailing white
 * space, plus "…". Thus the first frame already shows the trimmed text. A new text, style, line count or width
 * measures again.
 */
@Composable
internal fun EllipsisText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val maxWidth = constraints.maxWidth
        // The length of the text that stays before the "…", or null when the plain ellipsis is right.
        val keep = remember(text, style, maxLines, maxWidth, measurer) {
            val layout = measurer.measure(
                text = text,
                style = style,
                overflow = TextOverflow.Ellipsis,
                maxLines = maxLines,
                constraints = Constraints(maxWidth = maxWidth),
            )
            trimmedCut(text, layout)
        }
        val shown = if (keep == null) {
            text
        } else {
            buildAnnotatedString {
                append(text.subSequence(0, keep))
                append(ELLIPSIS)
            }
        }
        Text(
            text = shown,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = if (keep == null) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

/**
 * The length of [text] to keep before an "…" when the ellipsis of [layout] comes after white space, or null when
 * the layout has no ellipsis or the ellipsis comes after a visible character.
 */
internal fun trimmedCut(text: CharSequence, layout: TextLayoutResult): Int? {
    val last = layout.lineCount - 1
    val end = if (last >= 0 && layout.isLineEllipsized(last)) layout.getLineEnd(last, visibleEnd = true) else 0
    val afterSpace = end in 1..text.length && text[end - 1].isWhitespace()
    return if (afterSpace) text.subSequence(0, end).trimEnd().length.takeIf { it > 0 } else null
}

private const val ELLIPSIS = '…'
