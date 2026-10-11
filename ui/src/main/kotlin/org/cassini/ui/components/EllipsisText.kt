// Composables are nouns in PascalCase.
@file:Suppress("FunctionNaming")

package org.cassini.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow

/**
 * A [Text] of at most [maxLines] lines that ends with "…" when it is cut, with no space before the "…".
 * `TextOverflow.Ellipsis` cuts at a character and can keep the space before the cut ("il …"); the boards never show
 * that space. When the first layout puts the ellipsis after white space, the text is drawn again as the part before
 * the cut, without its trailing white space, plus "…". A new text, line count or width measures again.
 */
@Composable
internal fun EllipsisText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    // The width of the layout that found the cut, and the length of the text that stays before the "…".
    var cut by remember(text, maxLines) { mutableStateOf<Pair<Int, Int>?>(null) }
    val current = cut
    val shown = if (current == null) {
        text
    } else {
        buildAnnotatedString {
            append(text.subSequence(0, current.second))
            append(ELLIPSIS)
        }
    }
    Text(
        text = shown,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = if (current == null) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { layout ->
            val width = layout.layoutInput.constraints.maxWidth
            if (current == null) {
                trimmedCut(text, layout)?.let { cut = width to it }
            } else if (current.first != width) {
                cut = null
            }
        },
    )
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
