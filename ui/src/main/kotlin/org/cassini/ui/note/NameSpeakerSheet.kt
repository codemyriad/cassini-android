// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.components.Avatar
import org.cassini.ui.components.CassiniCheckboxRow
import org.cassini.ui.components.CassiniSheet
import org.cassini.ui.components.CassiniTextField
import org.cassini.ui.components.DISABLED_ALPHA
import org.cassini.ui.components.LikelyTag
import org.cassini.ui.components.NAME_MAX_LENGTH
import org.cassini.ui.components.PrimaryButton
import org.cassini.ui.components.SecondaryButton
import org.cassini.ui.components.SecondaryButtonSize
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.components.TextButton
import org.cassini.ui.components.display
import org.cassini.ui.components.overhang
import org.cassini.ui.components.touchTarget
import org.cassini.ui.format.Durations
import org.cassini.ui.format.LocalFormat
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import java.text.BreakIterator

/**
 * The Name a speaker sheet (board 04; features.md section 4) in its [CassiniSheet] window. Every control sends a
 * [NameSheetEvent]; a swipe or a scrim tap sends [onDismiss]. The ViewModel's `SpeakerNaming` reducer owns the
 * state, so the field shows [NameSheetUi.name] as given.
 */
@Composable
fun NameSpeakerSheet(ui: NameSheetUi, onEvent: (NameSheetEvent) -> Unit, onDismiss: () -> Unit) {
    CassiniSheet(onDismissRequest = onDismiss) { NameSpeakerSheetContent(ui, onEvent) }
}

/**
 * The sheet's content, also drawn inline in screenshots (`SheetBoardFrame`): padding 16 (below the handle) / 20 /
 * 24 with a 16 dp gap. The header (avatar, title, "Speaks for …", Play sample), the Name field, the saved voices
 * card, "Not <name>?", the same-person or rename-saved question, Remember, and the footer (Merge into…, Cancel, Save
 * name). The content scrolls when it does not fit above the keyboard.
 *
 * Save with a question open ([SaveState.BLOCKED_BY_QUESTION]) looks enabled. A press (or IME Done) sends
 * [NameSheetEvent.SaveBlocked], moves focus to the question, which then reports "Answer the question above first"
 * through its polite live region, and pulses the card's border once (the system animator scale applies, so with
 * animations off there is no pulse).
 */
@Composable
@Suppress("LongMethod") // One column of board 04, top to bottom.
fun NameSpeakerSheetContent(ui: NameSheetUi, onEvent: (NameSheetEvent) -> Unit, modifier: Modifier = Modifier) {
    val questionFocus = remember { FocusRequester() }
    var pulses by remember { mutableIntStateOf(0) }
    val question = question(ui)
    val blocked = {
        onEvent(NameSheetEvent.SaveBlocked)
        if (question != null) {
            pulses++
            questionFocus.requestFocus()
        }
    }
    val save = {
        when (ui.save) {
            SaveState.ENABLED -> onEvent(NameSheetEvent.Save)
            SaveState.BLOCKED_BY_QUESTION -> blocked()
            SaveState.DISABLED -> Unit
        }
    }
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            // Bottom 25: board 04's Cancel is 50 dp tall (a content-box border) and centres Save in its row.
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 25.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SheetHeader(ui, onEvent)
        CassiniTextField(
            value = ui.name,
            onValueChange = { onEvent(NameSheetEvent.NameChanged(it.take(NAME_MAX_LENGTH))) },
            label = stringResource(R.string.ui_name_label),
            showFocus = if (ui.nameFieldFocused) true else null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            // Board 04 sets the 13 sp label in a 16 px line box (19.5 dp): 3 dp more than Android's own line.
            modifier = Modifier.padding(top = 3.dp),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
        if (ui.voices.isNotEmpty()) VoicesCard(ui, onEvent)
        ui.notName?.let { name ->
            // The text button's 14 dp padding hangs into the sheet padding, so its text starts at the content edge.
            TextButton(
                text = stringResource(R.string.ui_name_not, name),
                onClick = { onEvent(NameSheetEvent.NotName) },
                modifier = Modifier.offset(x = (-14).dp),
            )
        }
        if (question != null) {
            QuestionCard(question, pulses, onEvent, Modifier.focusRequester(questionFocus))
        }
        CassiniCheckboxRow(
            checked = ui.remember,
            onCheckedChange = { onEvent(NameSheetEvent.RememberChanged(it)) },
            title = stringResource(R.string.ui_name_remember),
            subtitle = rememberSubtitle(ui),
            enabled = ui.rememberEnabled,
        )
        Footer(ui, onEvent, onSave = save, onBlocked = blocked)
    }
}

/** Avatar 40, title `sheetTitle`, "Speaks for 3 min 12 s in this note", and the 44 dp Play sample button. */
@Composable
private fun SheetHeader(ui: NameSheetUi, onEvent: (NameSheetEvent) -> Unit) {
    val c = CassiniTheme.colors
    val label = ui.label.display()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(text = avatarText(ui.label, label), tone = ui.tone)
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                modifier = Modifier.semantics { heading() },
                style = CassiniTheme.type.sheetTitle,
                color = c.ink,
            )
            Text(text = Durations.speaksFor(ui.speakingMs), style = CassiniTheme.type.caption, color = c.text2)
        }
        PlaySampleButton(
            sampling = ui.sampling,
            enabled = ui.canSample,
            onClick = { onEvent(NameSheetEvent.PlaySample) },
        )
    }
}

/** The avatar's text: the placeholder number, or the first character (grapheme) of the name. */
@Composable
private fun avatarText(name: SpeakerNameUi, label: String): String {
    if (name is SpeakerNameUi.Placeholder) return name.number.toString()
    val locale = LocalFormat.current.locale
    val breaks = BreakIterator.getCharacterInstance(locale).apply { setText(label) }
    val end = breaks.next()
    return if (end == BreakIterator.DONE) "" else label.substring(0, end).uppercase(locale)
}

/**
 * Play sample: a 44 dp surface circle with a 1 dp line2 border and the filled Play 18 (Pause while [sampling]).
 * The 48 dp touch area overhangs the visual by 2 dp on each side, so the row keeps the board's 44 dp height.
 */
@Composable
private fun PlaySampleButton(sampling: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = CassiniTheme.colors
    val description = stringResource(if (sampling) R.string.ui_name_stop_sample else R.string.ui_name_play_sample)
    Box(
        modifier = Modifier
            .overhang(2.dp)
            .touchTarget()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(c.surface)
                .border(1.dp, c.line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CassiniIcon(if (sampling) Glyph.Pause else Glyph.Play, 18.dp, 0f, c.ink, description)
        }
    }
}

/**
 * "Saved voices, closest first" (or "Saved voices" without a voiceprint) and the list card: 1 dp line border,
 * radius 14; rows at least 52 dp, padding 14, gap 10, `body16`; the Likely row on surfaceWash with its tag; 1 dp
 * line dividers; then "Show all" in accentInk when more voices exist.
 */
@Composable
private fun VoicesCard(ui: NameSheetUi, onEvent: (NameSheetEvent) -> Unit) {
    val c = CassiniTheme.colors
    val shape = CassiniTheme.shapes.row
    Column {
        Text(
            text = stringResource(if (ui.ranked) R.string.ui_name_voices_ranked else R.string.ui_name_voices),
            modifier = Modifier
                .padding(bottom = 6.dp)
                .semantics { heading() },
            style = CassiniTheme.type.fieldLabel,
            color = c.text3,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, c.line, shape),
        ) {
            ui.voices.forEachIndexed { i, voice ->
                if (i > 0) RowDivider()
                VoiceRow(
                    text = voice.name,
                    onClick = { onEvent(NameSheetEvent.PickVoice(voice.id)) },
                    likely = voice.likely,
                )
            }
            if (ui.showAll) {
                RowDivider()
                VoiceRow(
                    text = stringResource(R.string.ui_name_show_all),
                    onClick = { onEvent(NameSheetEvent.ShowAll) },
                    accent = true,
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(text: String, onClick: () -> Unit, likely: Boolean = false, accent: Boolean = false) {
    val c = CassiniTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (likely) Modifier.background(c.surfaceWash) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = CassiniTheme.type.body16,
            color = if (accent) c.accentInk else c.ink,
        )
        if (likely) LikelyTag(stringResource(R.string.ui_name_likely))
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CassiniTheme.colors.line),
    )
}

/** "Future notes will name Anna automatically. …", or why Remember is disabled. */
@Composable
private fun rememberSubtitle(ui: NameSheetUi): String {
    if (!ui.rememberEnabled) {
        return when (ui.rememberReason) {
            RememberReason.NO_MODEL -> stringResource(R.string.ui_name_remember_no_model)
            RememberReason.TOO_LITTLE_SPEECH, null -> stringResource(R.string.ui_name_remember_too_little)
        }
    }
    val name = ui.name.trim()
    return if (name.isEmpty()) {
        stringResource(R.string.ui_name_remember_body_unnamed)
    } else {
        stringResource(R.string.ui_name_remember_body, name)
    }
}

/**
 * The footer, 4 dp below the gap: "Merge into…" at the start (its text 14 dp in, as on the board), then Cancel and
 * Save name 8 dp apart at the end. Save is disabled only for [SaveState.DISABLED].
 */
@Composable
private fun Footer(
    ui: NameSheetUi,
    onEvent: (NameSheetEvent) -> Unit,
    onSave: () -> Unit,
    onBlocked: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ui.canMerge) {
            TextButton(stringResource(R.string.ui_name_merge_into), onClick = { onEvent(NameSheetEvent.MergeInto) })
        } else {
            Spacer(Modifier)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = stringResource(R.string.ui_action_cancel),
                onClick = { onEvent(NameSheetEvent.Cancel) },
                size = SecondaryButtonSize.Regular,
            )
            PrimaryButton(
                text = stringResource(R.string.ui_name_save),
                onClick = onSave,
                enabled = ui.save != SaveState.DISABLED,
                blockedReason = if (ui.save == SaveState.BLOCKED_BY_QUESTION) {
                    stringResource(R.string.ui_name_answer_first)
                } else {
                    null
                },
                onBlocked = onBlocked,
            )
        }
    }
}
