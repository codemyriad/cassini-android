// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.note

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cassini.ui.R
import org.cassini.ui.components.InkButton
import org.cassini.ui.components.InkButtonSize
import org.cassini.ui.components.SecondaryButton
import org.cassini.ui.components.SecondaryButtonSize
import org.cassini.ui.theme.CassiniTheme

/** How long the question card's border pulses after a blocked Save. */
private const val PULSE_MS = 600

/**
 * How long the question card's state description stays clear after a blocked Save: longer than the 100 ms in which
 * Compose merges accessibility changes, so the clear and the new value both reach TalkBack.
 */
private const val ANNOUNCE_GAP_MS = 250L

/** An open question of the sheet: the bold [saved] sentence, the question, and its two answers. */
internal data class Question(
    val saved: String,
    val ask: String,
    val yes: String,
    val yesEvent: NameSheetEvent,
)

/** The same-person question (features.md section 4, item 4) or the rename-saved question (item 5), else null. */
@Composable
internal fun question(ui: NameSheetUi): Question? {
    val same = ui.samePerson
    val rename = ui.renameSaved
    return when {
        same != null -> Question(
            saved = stringResource(R.string.ui_name_same_person_saved, same),
            ask = stringResource(R.string.ui_name_same_person_question),
            yes = stringResource(R.string.ui_name_same_person),
            yesEvent = NameSheetEvent.SamePerson,
        )
        rename != null -> Question(
            saved = stringResource(R.string.ui_name_rename_saved_saved_as, rename),
            ask = stringResource(R.string.ui_name_rename_saved_question),
            yes = stringResource(R.string.ui_name_rename_saved),
            yesEvent = NameSheetEvent.RenameSaved,
        )
        else -> null
    }
}

/**
 * The question card: amberWash, radius 14, padding 12/14 (bottom 8: the buttons' 48 dp layout holds 4 dp below
 * their 40 dp visual), `body14` ink with the first sentence bold, then the answers 4 dp below (8 on the board,
 * less the same slack): `InkButton` medium and `SecondaryButton` medium plain "Someone else". The card is focusable,
 * so a blocked Save can move focus to it; [pulses] counts the blocked presses, and each one says "Answer the
 * question above first" (features.md section 4).
 */
@Composable
internal fun QuestionCard(
    question: Question,
    pulses: Int,
    onEvent: (NameSheetEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CassiniTheme.colors
    val shape = CassiniTheme.shapes.row
    val pulse = remember { Animatable(0f) }
    // The live region speaks when its state description changes. A second blocked press would leave it unchanged,
    // so each press clears it first and sets it again a moment later: TalkBack then speaks it on every press.
    var announced by remember { mutableStateOf(false) }
    LaunchedEffect(pulses) {
        if (pulses > 0) {
            launch {
                announced = false
                delay(ANNOUNCE_GAP_MS)
                announced = true
            }
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(PULSE_MS))
        }
    }
    val answerFirst = stringResource(R.string.ui_name_answer_first)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.amberWash)
            .border(2.dp, c.amberInk.copy(alpha = pulse.value), shape)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                if (announced) stateDescription = answerFirst
            }
            .focusable()
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
    ) {
        Text(
            text = boldFirst(question.saved, question.ask),
            style = CassiniTheme.type.body14,
            color = c.ink,
        )
        FlowRow(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InkButton(
                text = question.yes,
                onClick = { onEvent(question.yesEvent) },
                size = InkButtonSize.Medium,
            )
            SecondaryButton(
                text = stringResource(R.string.ui_name_someone_else),
                onClick = { onEvent(NameSheetEvent.SomeoneElse) },
                size = SecondaryButtonSize.MediumPlain,
            )
        }
    }
}

private fun boldFirst(bold: String, rest: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(bold) }
    append(' ')
    append(rest)
}
