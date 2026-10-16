// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.cassini.ui.R
import org.cassini.ui.theme.CassiniColors
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.SANS_NORMAL_LINE_HEIGHT
import java.util.Arrays

/**
 * A test probe: when provided, every composition of a [TurnParagraph] calls it once. The recomposition test uses it
 * to check that changing the active word recomposes only the paragraphs that hold the old or the new active word.
 */
internal val LocalTurnParagraphProbe = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * One turn's paragraph: a single `Text` built from [words] (joined by spaces, see [turnText]).
 *
 * [TurnMode.Read] (`transcript`, ink, or text3 for a [placeholder] speaker): a tap calls [onWordTap] with the index of
 * the word under it; a tap on a space reaches the nearer word on the same line. The pressed word gets the accentTint
 * background. [activeIndex] is the word being played: its glyphs turn onAccentFill and a rounded accentFill
 * rectangle (radius 4, 3 dp wider on each side, the height of the font's content area as on board 03) is drawn
 * behind them from the text layout, one rectangle per line when the word wraps, so the text never reflows.
 *
 * [TurnMode.Draft] (`draft`, text3; words from [unsettledFrom] on in text4): plain text, taps do nothing.
 *
 * The paragraph that holds the active word has [ActiveWordStart] in its semantics: that word's start (ms), for tests.
 * It is not given to accessibility services.
 *
 * [matches] are search hits on the amberTint background; the current one is also set in weight 600.
 * [onPlayFromStart] adds the "Play from start of turn" accessibility action. [onActiveLine] receives the top of the
 * active word's line (px, in this paragraph's coordinates) on every text layout and when the active word changes.
 * The Note screen passes it only to the turn that holds the active word, to follow playback.
 *
 * Known gaps against ui.md section 6 and features.md section 3.3, for the owner's review:
 * - The words are not links, so TalkBack has no action for each word, only "Play from start of turn". A tap gives
 *   a position, and the paragraph finds the word at that position in its text layout. A link for each word made
 *   one child node for each word, and long notes were then too slow to scroll, to open and to draw.
 *
 * The annotated text is built once per words, mode, unsettled range and matches. The active and the pressed word are
 * drawn from the text layout in the draw phase, so a new [activeIndex] does not lay out the text again. In a list
 * only the turns with the old and the new active word recompose. The screen keys the list items by turn id and
 * passes null to every other turn.
 */
@Composable
@Suppress("LongParameterList") // ui.md section 6: words, active word, unsettled range, matches, tap, mode.
fun TurnParagraph(
    words: ImmutableList<WordUi>,
    activeIndex: Int?,
    onWordTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
    mode: TurnMode = TurnMode.Read,
    placeholder: Boolean = false,
    unsettledFrom: Int? = null,
    matches: TurnMatches = TurnMatches.None,
    onPlayFromStart: (() -> Unit)? = null,
    onActiveLine: ((top: Float) -> Unit)? = null,
) {
    val probe = LocalTurnParagraphProbe.current
    if (probe != null) SideEffect { probe() }
    val c = CassiniTheme.colors
    val style = if (mode == TurnMode.Read) CassiniTheme.type.transcript else CassiniTheme.type.draft
    val color = if (mode == TurnMode.Draft || placeholder) c.text3 else c.ink
    val tap by rememberUpdatedState(onWordTap)
    val starts = remember(words) { wordStarts(words) }
    val text = remember(words, mode, unsettledFrom, matches, c) {
        paragraph(words, starts, mode, unsettledFrom, matches, c)
    }
    val activeWord = activeIndex?.takeIf { mode == TurnMode.Read && it in words.indices }
    val active = activeWord?.let { starts[it] until starts[it] + words[it].text.length }?.takeIf { !it.isEmpty() }
    val highlights = remember { Highlights() }
    val line by rememberUpdatedState(onActiveLine)
    val currentActive by rememberUpdatedState(active)
    if (active != null && onActiveLine != null) {
        // A new active word does not lay out the text again, so this effect reports its line too.
        LaunchedEffect(active) {
            snapshotFlow { highlights.layout }.collect { if (it != null) reportActiveLine(it, active, line) }
        }
    }
    // The same callback and tap modifier in each composition, so a new active word does not update the text node
    // or the tap detection.
    val onLayout = remember(highlights) {
        fun(result: TextLayoutResult) {
            highlights.layout = result
            reportActiveLine(result, currentActive, line)
        }
    }
    val taps = remember(words, starts, mode, highlights) {
        if (mode == TurnMode.Read) Modifier.wordTaps(words, starts, highlights) { tap(it) } else Modifier
    }
    val playLabel = stringResource(R.string.ui_turn_play_from_start)
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .then(taps)
            .drawWithContent {
                with(highlights) { draw(active, style.fontSize.toPx() * SANS_NORMAL_LINE_HEIGHT, c) }
            }
            .paragraphSemantics(activeWord?.takeIf { active != null }, words, playLabel, onPlayFromStart),
        style = style,
        color = color,
        onTextLayout = onLayout,
    )
}

/**
 * The text layout and the pressed word of one paragraph. Only the draw phase, the taps and the line effect read
 * them, so a new layout or a press does not recompose the paragraph.
 */
private class Highlights {
    var layout by mutableStateOf<TextLayoutResult?>(null)
    var pressed by mutableStateOf<IntRange?>(null)
    private val activeRects = WordRects()
    private val pressedRects = WordRects()

    /**
     * Draws the paragraph with the pressed word on accentTint and the [active] word on accentFill in onAccentFill.
     * [height] is the height of the word rectangles.
     */
    fun ContentDrawScope.draw(active: IntRange?, height: Float, c: CassiniColors) {
        val result = layout
        if (result == null) {
            drawContent()
            return
        }
        pressed?.let { drawWord(pressedRects.of(this, result, it, height), c.accentTint) }
        val rects = if (active != null) activeRects.of(this, result, active, height) else emptyList()
        drawWord(rects, c.accentFill)
        drawContent()
        drawActiveGlyphs(result, rects, c.accentFill, c.onAccentFill)
    }

    /** Draws [rects] in [color] with 4 dp corners. */
    private fun DrawScope.drawWord(rects: List<Rect>, color: Color) {
        val radius = CornerRadius(4.dp.toPx())
        for (rect in rects) drawRoundRect(color, rect.topLeft, rect.size, radius)
    }

    /**
     * Draws the active word's glyphs in [glyphs] over the paragraph. In each of [rects] it draws the [fill]
     * background again, which hides the glyphs in the paragraph colour, and then the same [layout] in [glyphs].
     */
    private fun DrawScope.drawActiveGlyphs(layout: TextLayoutResult, rects: List<Rect>, fill: Color, glyphs: Color) {
        val radius = CornerRadius(4.dp.toPx())
        for (rect in rects) {
            // 1 px inside the edge, so the background's antialiased edge is drawn once.
            clipRect(rect.left + 1f, rect.top + 1f, rect.right - 1f, rect.bottom - 1f) {
                drawRoundRect(color = fill, topLeft = rect.topLeft, size = rect.size, cornerRadius = radius)
                drawText(layout, color = glyphs)
            }
        }
    }
}

/**
 * The word taps of a Read paragraph: [onTap] gets the index of the tapped word, and [highlights] holds the pressed
 * word while the finger is down. A drag cancels the tap, so the list still scrolls.
 */
private fun Modifier.wordTaps(
    words: List<WordUi>,
    starts: IntArray,
    highlights: Highlights,
    onTap: (Int) -> Unit,
): Modifier = pointerInput(words) {
    fun wordAt(position: Offset): Int? = highlights.layout?.let { wordAt(it, words, starts, position) }
    detectTapGestures(
        onPress = { position ->
            highlights.pressed = wordAt(position)?.let { starts[it] until starts[it] + words[it].text.length }
            tryAwaitRelease()
            highlights.pressed = null
        },
        onTap = { position -> wordAt(position)?.let(onTap) },
    )
}

/** The start (ms) of the word that a [TurnParagraph] shows as playing. Only tests read it. */
val ActiveWordStart = SemanticsPropertyKey<Long>("ActiveWordStart")

private var SemanticsPropertyReceiver.activeWordStart by ActiveWordStart

/** The start of the [active] word, for tests, and the "Play from start of turn" action of [onPlayFromStart]. */
private fun Modifier.paragraphSemantics(
    active: Int?,
    words: List<WordUi>,
    playLabel: String,
    onPlayFromStart: (() -> Unit)?,
): Modifier = semantics {
    if (active != null) activeWordStart = words[active].startMs
    if (onPlayFromStart != null) {
        customActions = listOf(
            CustomAccessibilityAction(playLabel) {
                onPlayFromStart()
                true
            },
        )
    }
}

/** Gives [onActiveLine] the top of the line that holds [active] in [layout]; nothing without either. */
private fun reportActiveLine(layout: TextLayoutResult, active: IntRange?, onActiveLine: ((Float) -> Unit)?) {
    if (onActiveLine == null || active == null || active.isEmpty()) return
    if (active.first < layout.layoutInput.text.length) {
        onActiveLine(layout.getLineTop(layout.getLineForOffset(active.first)))
    }
}

/**
 * The index of the word at [position] in [layout], or null without words. On the space between two words of the
 * tapped line, the word with the smaller horizontal distance wins.
 */
private fun wordAt(layout: TextLayoutResult, words: List<WordUi>, starts: IntArray, position: Offset): Int? {
    if (words.isEmpty()) return null
    val length = layout.layoutInput.text.length
    val offset = layout.getOffsetForPosition(position).coerceIn(0, length)
    val found = Arrays.binarySearch(starts, offset)
    var index = if (found >= 0) found else (-found - 2).coerceAtLeast(0)
    val line = layout.getLineForVerticalPosition(position.y)
    // Right of the last word of a wrapped line, the offset can be the start of the next line.
    if (index > 0 && starts[index] < length && layout.getLineForOffset(starts[index]) > line) index -= 1
    return nearerWord(layout, starts, index, offset, position)
}

/**
 * [index], or the word after it when [offset] is on the space between the two, both words are on the line of
 * [position] and the next word is nearer to it. The space is the character before the next word's start.
 */
private fun nearerWord(layout: TextLayoutResult, starts: IntArray, index: Int, offset: Int, position: Offset): Int {
    val next = index + 1
    val space = if (next < starts.size) starts[next] - 1 else -1
    val line = layout.getLineForVerticalPosition(position.y)
    val between = space > starts[index] && offset >= space && starts[next] < layout.layoutInput.text.length &&
        layout.getLineForOffset(space - 1) == line && layout.getLineForOffset(starts[next]) == line
    if (!between) return index
    val before = horizontalDistance(layout.getBoundingBox(space - 1), position.x)
    val after = horizontalDistance(layout.getBoundingBox(starts[next]), position.x)
    return if (after < before) next else index
}

/** The distance from [x] to [box] along the line; 0 inside it. */
private fun horizontalDistance(box: Rect, x: Float): Float = maxOf(box.left - x, x - box.right, 0f)

/** The paragraph text with its unsettled colour (Draft) and its search highlights. */
@Suppress("LongParameterList") // Everything the cached text depends on.
private fun paragraph(
    words: List<WordUi>,
    starts: IntArray,
    mode: TurnMode,
    unsettledFrom: Int?,
    matches: TurnMatches,
    c: CassiniColors,
): AnnotatedString = buildAnnotatedString {
    append(turnText(words))
    if (mode != TurnMode.Read && unsettledFrom != null && unsettledFrom in words.indices) {
        addStyle(SpanStyle(color = c.text4), starts[unsettledFrom], length)
    }
    matches.ranges.forEachIndexed { index, range ->
        val start = range.first.coerceIn(0, length)
        val end = (range.last + 1).coerceIn(start, length)
        val current = index == matches.current
        addStyle(SpanStyle(background = c.amberTint, fontWeight = if (current) FontWeight.W600 else null), start, end)
    }
}

/**
 * The background rectangles of one word. They stay until the layout or the word changes, so a frame makes no path.
 * Per line of the word: the bounds of its glyphs from `getPathForRange`, 3 dp wider on each side, [of]'s height
 * tall and centred on the line.
 */
private class WordRects {
    private var layout: TextLayoutResult? = null
    private var range: IntRange? = null
    private var rects: List<Rect> = emptyList()

    fun of(density: Density, layout: TextLayoutResult, range: IntRange, height: Float): List<Rect> {
        if (layout !== this.layout || range != this.range) {
            this.layout = layout
            this.range = range
            rects = measure(density, layout, range, height)
        }
        return rects
    }

    private fun measure(density: Density, layout: TextLayoutResult, range: IntRange, height: Float): List<Rect> {
        val end = range.last + 1
        if (range.isEmpty() || end > layout.layoutInput.text.length) return emptyList()
        val outset = with(density) { 3.dp.toPx() }
        return (layout.getLineForOffset(range.first)..layout.getLineForOffset(range.last)).mapNotNull { line ->
            val start = maxOf(range.first, layout.getLineStart(line))
            val stop = minOf(end, layout.getLineEnd(line, visibleEnd = true))
            if (start >= stop) return@mapNotNull null
            val bounds = layout.getPathForRange(start, stop).getBounds()
            val centre = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            Rect(bounds.left - outset, centre - height / 2f, bounds.right + outset, centre + height / 2f)
        }
    }
}

@Preview
@Composable
internal fun TurnParagraphPreview() {
    val words = (
        "Sì, quattrocento. Il problema è la seconda consegna: quindi la consegna slitta a giovedì, but the price " +
            "stays as agreed in the March order."
        ).split(' ').mapIndexed { index, word -> WordUi(word, index * 400L) }.toImmutableList()
    CassiniTheme {
        Column(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.surface)
                .padding(horizontal = 20.dp),
        ) {
            TurnParagraph(words, activeIndex = 8, onWordTap = {})
            TurnParagraph(words, activeIndex = null, onWordTap = {}, mode = TurnMode.Draft, unsettledFrom = 12)
            TurnParagraph(words, null, {}, matches = TurnMatches(persistentListOf(36..42), current = 0))
        }
    }
}
