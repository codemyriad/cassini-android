// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md sections 6 and 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.components.Banner
import org.cassini.ui.components.FormatBadge
import org.cassini.ui.components.IconButton44
import org.cassini.ui.components.RecordingControls
import org.cassini.ui.components.RecordingStatus
import org.cassini.ui.components.RecordingTimer
import org.cassini.ui.components.TITLE_MAX_LENGTH
import org.cassini.ui.components.Waveform
import org.cassini.ui.components.rememberSyncedText
import org.cassini.ui.components.screenContentWidth
import org.cassini.ui.format.Counts
import org.cassini.ui.format.Durations
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import org.cassini.ui.theme.LightColors

/**
 * The Recording screen (board 01, features.md section 2; ui.md section 8). It is dark in both app themes: every
 * colour comes from `CassiniTheme.recording`, and the banner keeps its light tones.
 *
 * A Column with padding 16/16/28 inside the safe drawing insets: the 48 dp header (Minimise, the status and the
 * format badge, spaced as the board), the title block 28 dp below, the banner slot 8 dp below that, the centre
 * (timer, waveform and caption, 28 dp apart and centred in the free height) and the controls.
 *
 * The header chevron sends [RecordingEvent.Minimise]: the recording continues. System back is not handled here: the
 * navigation in :app pops `RecordingKey` with the predictive pop of ui.md section 9, and that pop minimises too.
 * The banner's "Open settings" sends [RecordingEvent.OpenSettings]; its other actions send
 * [RecordingEvent.BannerAction].
 *
 * The status shows only while the capture runs or is paused. Before the capture starts and while it is saved, the
 * caption tells why ("Cassini needs the microphone to record.", "Starting the microphone…", "Saving the
 * recording…") and Pause and Finish are disabled.
 */
@Composable
fun RecordingScreen(
    state: RecordingUiState,
    onEvent: (RecordingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rec = CassiniTheme.recording
    Column(
        modifier
            .fillMaxSize()
            .background(rec.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .screenContentWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 28.dp),
    ) {
        RecordingHeader(state, onEvent)
        TitleField(state.title, onTitle = { onEvent(RecordingEvent.Title(it)) })
        state.banner?.let { spec ->
            Banner(
                spec = spec,
                onAction = { action ->
                    onEvent(
                        if (action == BannerAction.OPEN_SETTINGS) {
                            RecordingEvent.OpenSettings
                        } else {
                            RecordingEvent.BannerAction(spec.id, action)
                        },
                    )
                },
                modifier = Modifier.padding(top = 8.dp),
                colors = LightColors,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RecordingTimer(state.elapsedMs)
            Waveform(state.levels)
            Text(
                text = caption(state),
                modifier = Modifier.widthIn(max = 300.dp),
                style = CassiniTheme.type.body14,
                color = rec.text2,
                textAlign = TextAlign.Center,
            )
        }
        val capturing = state.phase == RecordingPhase.Recording || state.phase == RecordingPhase.Paused
        RecordingControls(
            paused = state.phase == RecordingPhase.Paused,
            onLibrary = { onEvent(RecordingEvent.OpenLibrary) },
            onPauseResume = { onEvent(RecordingEvent.PauseResume) },
            onFinish = { onEvent(RecordingEvent.Finish) },
            captureEnabled = capturing,
        )
    }
}

/**
 * The header row, 48 dp: Minimise (a 44 dp visual; the 48 dp layout overlaps 2 dp on each side, so the space
 * between the items is the board's), the status and the format badge ("Opus 48 kHz"), spaced between as the board
 * draws them.
 */
@Composable
private fun RecordingHeader(state: RecordingUiState, onEvent: (RecordingEvent) -> Unit) {
    val rec = CassiniTheme.recording
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton44(
            glyph = Glyph.ChevronDown,
            contentDescription = stringResource(R.string.ui_recording_minimise),
            onClick = { onEvent(RecordingEvent.Minimise) },
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val slack = 2.dp.roundToPx()
                layout(placeable.width - 2 * slack, placeable.height) { placeable.place(-slack, 0) }
            },
            tint = rec.text,
        )
        when (state.phase) {
            RecordingPhase.Recording -> RecordingStatus(paused = false)
            RecordingPhase.Paused -> RecordingStatus(paused = true)
            else -> Box(Modifier)
        }
        FormatBadge(Counts.captureFormat(state.format == CaptureFormat.M4A, state.sampleRateHz))
    }
}

/**
 * The title block, 28 dp below the header: the label "Title" (13 sp rec.text2, 3 dp lower, see below), 4 dp, then the field row with a
 * 1 dp rec.line bottom border and 6 dp below the text: the title in `screenTitle` rec.text (4 dp above and below,
 * as the board's input), the caret in rec.status, and the decorative Pencil 18/1.8 in rec.text2, 8 dp after it.
 * The label is inside the field's decoration, so TalkBack reads it with the field.
 */
@Composable
private fun TitleField(title: String, onTitle: (String) -> Unit) {
    val rec = CassiniTheme.recording
    val t = CassiniTheme.type
    val focus = LocalFocusManager.current
    // The field shows its own copy of the title and sends each change; a late state keeps the typed text.
    val text = rememberSyncedText(title)
    BasicTextField(
        value = text.value,
        onValueChange = { text.edit(it, TITLE_MAX_LENGTH, onTitle) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
        textStyle = t.screenTitle.copy(color = rec.text),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        singleLine = true,
        cursorBrush = SolidColor(rec.status),
        decorationBox = { inner ->
            Column {
                // The board's label is inline in a 16 px line box: its glyphs sit 3 dp lower than a 13 sp line.
                Text(
                    text = stringResource(R.string.ui_title_label),
                    modifier = Modifier.padding(top = 3.dp),
                    style = t.caption,
                    color = rec.text2,
                )
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .drawBehind {
                            val line = 1.dp.toPx()
                            drawRect(rec.line, Offset(0f, size.height - line), Size(size.width, line))
                        }
                        .padding(bottom = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                    ) { inner() }
                    CassiniIcon(Glyph.Pencil, 18.dp, 1.8f, rec.text2, null)
                }
            }
        },
    )
}

/** The caption under the waveform: the hint while capturing, else why nothing is captured yet. */
@Composable
private fun caption(state: RecordingUiState): String = when (state.phase) {
    RecordingPhase.NeedsPermission -> stringResource(R.string.ui_recording_needs_permission)
    RecordingPhase.Starting -> stringResource(R.string.ui_recording_starting)
    RecordingPhase.Stopping -> stringResource(R.string.ui_recording_stopping)
    RecordingPhase.Recording, RecordingPhase.Paused -> when (val hint = state.hint) {
        is RecordingHint.Space -> Durations.spaceHint(hint.ms)
        null -> stringResource(R.string.ui_recording_hint)
    }
}
