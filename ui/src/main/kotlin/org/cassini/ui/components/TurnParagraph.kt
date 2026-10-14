// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.cassini.ui.R
import org.cassini.ui.theme.CassiniColors
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.SANS_NORMAL_LINE_HEIGHT

/**
 * A test probe: when provided, every composition of a [TurnParagraph] calls it once. The recomposition test uses it
 * to check that changing the active word recomposes only the paragraphs that hold the old or the new active word.
 */
internal val LocalTurnParagraphProbe = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * One turn's paragraph: a single `Text` built from [words] (joined by spaces, see [turnText]).
 *
 * [TurnMode.Read] (`transcript`, ink, or text3 for a [placeholder] speaker): each word is a `LinkAnnotation.Clickable`;
 * a tap calls [onWordTap] with the word's index (a tap on a space reaches the nearer word); a pressed word gets the
 * accentTint background. [activeIndex] is the word being played: its glyphs turn onAccentFill and a rounded accentFill
 * rectangle (radius 4, 3 dp wider on each side, the height of the font's content area as on board 03) is drawn
 * behind them from the text layout, one rectangle per line when the word wraps, so the text never reflows.
 *
 * [TurnMode.Draft] (`draft`, text3; words from [unsettledFrom] on in text4): plain text, the words are not links.
 *
 * The paragraph that holds the active word has [ActiveWordStart] in its semantics: that word's start (ms), for tests.
 * It is not given to accessibility services.
 *
 * [matches] are search hits on the amberTint background; the current one is also set in weight 600.
 * [onPlayFromStart] adds the "Play from start of turn" accessibility action. [onActiveLine] receives the top of the
 * active word's line (px, in this paragraph's coordinates) on every text layout, so also when the word changes.
 * The Note screen passes it only to the turn that holds the active word, to follow playback.
 *
 * Known gaps against ui.md section 6 and features.md section 3.3, for the owner's review:
 * - The word links have no "Play from here" click label. `LinkAnnotation` takes no label, and on Android Compose
 *   hides the link nodes from accessibility (`isAccessibilityIgnoredLink`) and gives TalkBack each word as a
 *   ClickableSpan in the paragraph's text (the Links menu), which has no action label. Our own labelled node per
 *   word would add one TalkBack stop per word.
 * - A link covers the word, not its following space (see [paragraph]). A tap on the space still reaches a word.
 *
 * The annotated text is built once per words, mode, unsettled range and matches; a new [activeIndex] only adds one
 * span, so in a list only the turns with the old and the new active word recompose. The screen keys the list items
 * by turn id and passes null to every other turn.
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
    val deferLinks = LocalDeferWordLinks.current
    val starts = remember(words) { wordStarts(words) }
    val base = remember(words, mode, unsettledFrom, matches, c, deferLinks) {
        paragraph(words, starts, mode, unsettledFrom, matches, c, links = !deferLinks) { index -> tap(index) }
    }
    val activeWord = activeIndex?.takeIf { mode == TurnMode.Read && it in words.indices }
    val active = activeWord?.let { starts[it] until starts[it] + words[it].text.length }
    val text = remember(base, active, c) {
        if (active == null || active.isEmpty()) {
            base
        } else {
            AnnotatedString.Builder(base)
                .apply { addStyle(SpanStyle(color = c.onAccentFill), active.first, active.last + 1) }
                .toAnnotatedString()
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val playLabel = stringResource(R.string.ui_turn_play_from_start)
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val result = layout
                if (result != null && active != null && !active.isEmpty()) {
                    drawActiveWord(result, active, c.accentFill, style.fontSize.toPx() * SANS_NORMAL_LINE_HEIGHT)
                }
            }
            .paragraphSemantics(activeWord?.takeIf { active?.isEmpty() == false }, words, playLabel, onPlayFromStart),
        style = style,
        color = color,
        onTextLayout = { result ->
            layout = result
            reportActiveLine(result, active, onActiveLine)
        },
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

/** The paragraph text with its links (Read), its unsettled colour (Draft) and its search highlights. */
@Suppress("LongParameterList") // Everything the cached text depends on.
private fun paragraph(
    words: List<WordUi>,
    starts: IntArray,
    mode: TurnMode,
    unsettledFrom: Int?,
    matches: TurnMatches,
    c: CassiniColors,
    links: Boolean = true,
    onTap: (Int) -> Unit,
): AnnotatedString = buildAnnotatedString {
    append(turnText(words))
    if (mode == TurnMode.Read && links) {
        val styles = TextLinkStyles(style = SpanStyle(), pressedStyle = SpanStyle(background = c.accentTint))
        words.forEachIndexed { index, word ->
            // The link covers the word only. A range with the following space would cross a line break where the
            // line wraps, and its bounds would then cover most of both lines. Taps on a space still reach a
            // neighbouring word: links are smaller than 48 dp, so Compose's extended hit testing takes the nearest.
            val start = starts[index]
            addLink(LinkAnnotation.Clickable("word-$index", styles) { onTap(index) }, start, start + word.text.length)
        }
    } else if (mode != TurnMode.Read && unsettledFrom != null && unsettledFrom in words.indices) {
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
 * Draws the active word's background: per line of [range], the bounds of its glyphs from `getPathForRange`, 3 dp wider
 * on each side, [height] tall and centred on the line, with 4 dp corners.
 */
private fun DrawScope.drawActiveWord(layout: TextLayoutResult, range: IntRange, color: Color, height: Float) {
    val end = range.last + 1
    if (end > layout.layoutInput.text.length) return
    val outset = 3.dp.toPx()
    val radius = CornerRadius(4.dp.toPx())
    for (line in layout.getLineForOffset(range.first)..layout.getLineForOffset(range.last)) {
        val start = maxOf(range.first, layout.getLineStart(line))
        val stop = minOf(end, layout.getLineEnd(line, visibleEnd = true))
        if (start >= stop) continue
        val bounds = layout.getPathForRange(start, stop).getBounds()
        val centre = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(bounds.left - outset, centre - height / 2f),
            size = Size(bounds.width + 2 * outset, height),
            cornerRadius = radius,
        )
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
