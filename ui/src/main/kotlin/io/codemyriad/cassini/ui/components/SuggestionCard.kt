// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.SpeakerTone
import io.codemyriad.cassini.ui.theme.sans

/** The question's style: 14 sp in the draft's 1.55 line height (board 02). */
private val QuestionStyle = sans(14, lh = 1.55f)

/** Where `%1$s` stands in a format string; the name is split out of the copy to set it in bold. */
private const val NAME_PLACEHOLDER = "%1\$s"

/**
 * The inline voice suggestion (board 02): surface, 1 dp line, radius 14, padding 10/12. The header is the speaker's
 * turn line ([speaker], [tone], [timeMs]); it is a 48 dp button across the top of the card that opens the Name sheet
 * ([onSpeaker], click label "Name this speaker"; null when naming is closed). Then "Is this **Luca**?" (14 sp, the
 * [name] in 700; 6 dp above, 8 below) and the buttons, 8 dp apart: "Yes, Luca" ([onYes], ink) and "Not Luca"
 * ([onNo]). The paragraph after the card starts 6 dp below it. The paddings in layout are measured on board 02:
 * 7.5 dp above the question (the board's speaker line is 17 dp, ours 16, and its 1.55 line box centres the text
 * lower), 3 dp below it (the 36 dp buttons sit in 48 dp) and 4 dp below the buttons' layout.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // ui.md section 6: the speaker line, the voice and three callbacks.
fun SuggestionCard(
    speaker: String,
    tone: SpeakerTone,
    timeMs: Long,
    name: String,
    onYes: () -> Unit,
    onNo: () -> Unit,
    onSpeaker: (() -> Unit)?,
    modifier: Modifier = Modifier,
    continuation: Boolean = false,
    over: String = "",
    crosstalk: Boolean = false,
    compact: Boolean = false,
) {
    if (compact) {
        Column(modifier) {
            TurnHeader(
                speaker,
                tone,
                timeMs,
                auto = false,
                onClick = onSpeaker,
                continuation = continuation,
                over = over,
                crosstalk = crosstalk,
            )
            CompactSuggestionQuestion(name, onYes, onNo)
        }
        return
    }
    val c = CassiniTheme.colors
    val shape = CassiniTheme.shapes.row
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape),
    ) {
        // The header button: the full width and the top 48 dp of the card, under the visible line. It covers part of
        // the question, which is plain text, and ends above the buttons.
        Box(
            Modifier
                .fillMaxWidth()
                .height(TurnHeaderHeight)
                .turnHeaderSemantics(
                    description = turnHeaderDescription(speaker, timeMs, auto = false, over, crosstalk),
                    clickLabel = stringResource(R.string.ui_turn_name_action),
                    onClick = onSpeaker,
                ),
        )
        // The board's padding sits inside its 1 dp CSS border; at the bottom, 4 dp below the 48 dp button layout.
        Column(Modifier.padding(start = 13.dp, end = 13.dp, top = 11.dp, bottom = 4.dp)) {
            TurnHeaderLine(
                name = speaker,
                tone = tone,
                timeMs = timeMs,
                auto = false,
                continuation = continuation,
                over = over,
                crosstalk = crosstalk,
            )
            Text(
                text = question(stringResource(R.string.ui_suggestion_question), name),
                modifier = Modifier.padding(top = 7.5.dp, bottom = 3.dp),
                style = QuestionStyle,
                color = c.ink,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InkButton(stringResource(R.string.ui_suggestion_yes, name), onClick = onYes, padding = 16.dp)
                SecondaryButton(stringResource(R.string.ui_suggestion_no, name), onClick = onNo, padding = 16.dp)
            }
        }
    }
}

/** The live draft keeps the question compact, with full spoken names on both 48dp actions. */
@Composable
private fun CompactSuggestionQuestion(name: String, onYes: () -> Unit, onNo: () -> Unit) {
    val c = CassiniTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(c.neutralTint).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            question(stringResource(R.string.ui_suggestion_question), name),
            Modifier.weight(1f).padding(vertical = 6.dp),
            style = QuestionStyle,
            color = c.ink,
        )
        CompactSuggestionAction(
            stringResource(R.string.ui_tx_suggestion_no),
            stringResource(R.string.ui_suggestion_no, name),
            false,
            onNo,
        )
        CompactSuggestionAction(
            stringResource(R.string.ui_tx_suggestion_yes),
            stringResource(R.string.ui_suggestion_yes, name),
            true,
            onYes,
        )
    }
}

@Composable
private fun CompactSuggestionAction(label: String, spoken: String, accent: Boolean, onClick: () -> Unit) {
    val c = CassiniTheme.colors
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken },
        contentPadding = PaddingValues(4.dp),
    ) {
        Text(
            label,
            Modifier.clearAndSetSemantics {}.clip(RoundedCornerShape(13.dp))
                .then(if (accent) Modifier.background(c.accent) else Modifier)
                .padding(horizontal = 11.dp, vertical = 3.dp),
            style = CassiniTheme.type.label14,
            color = if (accent) c.onAccentFill else c.text2,
        )
    }
}

/** [format] ("Is this %1$s?") with [name] in bold. */
private fun question(format: String, name: String): AnnotatedString = buildAnnotatedString {
    val at = format.indexOf(NAME_PLACEHOLDER)
    if (at < 0) {
        append(format)
    } else {
        append(format.substring(0, at))
        withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(name) }
        append(format.substring(at + NAME_PLACEHOLDER.length))
    }
}

@Preview
@Composable
internal fun SuggestionCardPreview() {
    CassiniTheme {
        Box(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.bg)
                .padding(20.dp),
        ) {
            SuggestionCard("Speaker 2", SpeakerTone.Unnamed, 1_742_000, "Luca", onYes = {}, onNo = {}, onSpeaker = {})
        }
    }
}
