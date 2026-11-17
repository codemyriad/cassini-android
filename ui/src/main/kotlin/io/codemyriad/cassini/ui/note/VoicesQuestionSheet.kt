// Composables are nouns in PascalCase; the numbers are the P2Voices-Phones board values (D103, L-60).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.CassiniSheet
import io.codemyriad.cassini.ui.components.PrimaryButton
import io.codemyriad.cassini.ui.components.PrimaryButtonSize
import io.codemyriad.cassini.ui.components.SecondaryButton
import io.codemyriad.cassini.ui.components.SecondaryButtonSize
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/**
 * The one voices question (D103, L-60; board P2Voices-Phones) in its [CassiniSheet] window over the open meeting. It
 * is asked once, the first time a meeting has more than one speaker. "Yes, remember voices" and "Not now" send
 * [onAnswer] with true or false; the ViewModel stores the answer and closes the sheet. The answer becomes the default
 * of "Remember this voice" ("Remember people I name" in Settings › Speakers). A swipe, a scrim tap or back sends
 * [onDismiss]: the question is not asked again and the default stays as it is.
 */
@Composable
fun VoicesQuestionSheet(
    onAnswer: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    ui: NoteSheet.VoicesQuestion = NoteSheet.VoicesQuestion(),
) {
    CassiniSheet(
        onDismissRequest = onDismiss,
        containerColor = CassiniTheme.colors.raised,
        confirmDismiss = {
            onDismiss()
            false
        },
    ) {
        VoicesQuestionContent(onAnswer, ui = ui)
    }
}

/**
 * The sheet's content, also drawn inline in screenshots: the people glyph (30 dp, accentInk), the title (24 sp bold),
 * the body (16 sp, text2), the D98 privacy line with the shield (`voices.setup.privacy.phone`, not the drawn "never
 * shared" line, PARITY §3), the filled primary and the tonal "Not now" (gap 10), then the kindness note and where to
 * change the answer (13 sp, centred). Padding 24 at the sides and 26 at the bottom; gap 14.
 */
@Composable
fun VoicesQuestionContent(
    onAnswer: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    ui: NoteSheet.VoicesQuestion = NoteSheet.VoicesQuestion(),
) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 26.dp)
            .testTag("voices-question"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CassiniIcon(Glyph.People, 30.dp, 1.6f, c.accentInk, null)
        Text(
            text = stringResource(R.string.ui_voices_setup_title),
            modifier = Modifier.semantics { heading() },
            style = t.libraryTitle.copy(fontWeight = FontWeight.Bold, lineHeight = 28.8.sp, letterSpacing = 0.sp),
            color = c.ink,
        )
        Text(
            text = stringResource(R.string.ui_voices_setup_body),
            style = t.heroBody,
            color = c.text2,
        )
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
            CassiniIcon(Glyph.Shield, 15.dp, 2f, c.teal, null, Modifier.padding(top = 2.dp))
            Spacer(Modifier.width(6.dp))
            Text(text = stringResource(R.string.ui_voices_setup_privacy), style = t.snippet, color = c.text2)
        }
        VoicesQuestionChoices(ui, onAnswer)
        if (ui.error) {
            Text(
                stringResource(R.string.ui_settings_transfer_choice_failed),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = t.caption,
                color = c.text2,
            )
        }
        Text(
            text = stringResource(R.string.ui_voices_setup_kindness) + "\n" +
                stringResource(R.string.ui_voices_setup_change_later),
            modifier = Modifier.fillMaxWidth(),
            style = t.caption.copy(lineHeight = 18.2.sp),
            color = c.text2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VoicesQuestionChoices(ui: NoteSheet.VoicesQuestion, onAnswer: (Boolean) -> Unit) {
    Column(
        Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PrimaryButton(
            text = stringResource(R.string.ui_voices_setup_enable),
            onClick = { onAnswer(true) },
            size = PrimaryButtonSize.Large,
            enabled = !ui.saving,
        )
        SecondaryButton(
            text = stringResource(R.string.ui_action_not_now),
            onClick = { onAnswer(false) },
            size = SecondaryButtonSize.Large,
            fillWidth = true,
            enabled = !ui.saving,
        )
    }
}
