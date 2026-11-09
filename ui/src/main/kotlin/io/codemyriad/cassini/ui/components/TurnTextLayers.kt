package io.codemyriad.cassini.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import io.codemyriad.cassini.ui.theme.CassiniColors
import io.codemyriad.cassini.ui.theme.speaker

/** Cached chip backgrounds precede the pressed/active fills and include internal spaces. */
internal fun chipBackgrounds(
    words: List<WordUi>,
    starts: IntArray,
    layout: TextLayoutResult,
    c: CassiniColors,
): List<Pair<Path, Color>> =
    words.mapIndexedNotNull { index, word ->
        word.interjectionTone?.let { tone ->
            val first = starts[index] - word.prefix.length
            val next = words.getOrNull(index + 1)
            val gap = if (word.suffix.isEmpty() && next?.interjectionTone == tone && next.prefix.isEmpty()) 1 else 0
            val end = starts[index] + word.text.length + word.suffix.length + gap
            layout.getPathForRange(first, end) to c.speaker(tone).tint
        }
    }

/** A second layout only when styled text changes; active-word ticks reuse it. */
internal fun TextMeasurer.highlightLayout(result: TextLayoutResult): TextLayoutResult {
    val input = result.layoutInput
    if (input.text.spanStyles.isEmpty()) return result
    return measure(
        text = highlightGlyphs(input.text),
        style = input.style,
        overflow = input.overflow,
        softWrap = input.softWrap,
        maxLines = input.maxLines,
        constraints = input.constraints,
        layoutDirection = input.layoutDirection,
        density = input.density,
        fontFamilyResolver = input.fontFamilyResolver,
    )
}

/** Preserve font metrics and emphasis, but let the active glyph colour override every span. */
internal fun highlightGlyphs(text: AnnotatedString): AnnotatedString = AnnotatedString(
    text.text,
    text.spanStyles.map { range ->
        AnnotatedString.Range(
            range.item.copy(color = Color.Unspecified, background = Color.Unspecified),
            range.start,
            range.end,
        )
    },
    text.paragraphStyles,
)
