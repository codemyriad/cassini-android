// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md sections 6 and 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.banner.BannerAction
import io.codemyriad.cassini.ui.components.Banner
import io.codemyriad.cassini.ui.components.FormatBadge
import io.codemyriad.cassini.ui.components.IconButton44
import io.codemyriad.cassini.ui.components.RecordingControls
import io.codemyriad.cassini.ui.components.RecordingStatus
import io.codemyriad.cassini.ui.components.RecordingTimer
import io.codemyriad.cassini.ui.components.TITLE_MAX_LENGTH
import io.codemyriad.cassini.ui.components.Waveform
import io.codemyriad.cassini.ui.components.rememberSyncedText
import io.codemyriad.cassini.ui.components.screenContentWidth
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph
import io.codemyriad.cassini.ui.theme.LightColors

/**
 * The Recording screen (board 01, features.md section 2; ui.md section 8). It is dark in both app themes: every
 * colour comes from `CassiniTheme.recording`, and the banner keeps its light tones.
 *
 * The editable title follows the status/format header. Timer, waveform and space hint share the remaining area;
 * that area scrolls in a short window so the capture controls remain visible. Stop is the centre action,
 * All meetings is on the left and labelled Pause/Resume is on the right (L-48/L-49).
 *
 * The header chevron sends [RecordingEvent.Minimise]: the recording continues. System back is not handled here: the
 * navigation in :app pops `RecordingKey` with the predictive pop of ui.md section 9, and that pop minimises too.
 * The banner's "Open settings" sends [RecordingEvent.OpenSettings]; its other actions send
 * [RecordingEvent.BannerAction].
 *
 * The status shows only while the capture runs or is paused. Before the capture starts and while it is saved, the
 * caption tells why ("Cassini needs the microphone to record.", "Starting the microphone…", "Saving the
 * recording…") and Pause and Stop are disabled.
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
            .padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 34.dp),
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
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RecordingTimer(state.elapsedMs)
            Waveform(state.levels, running = state.phase == RecordingPhase.Recording)
            Text(
                text = caption(state),
                modifier = Modifier.widthIn(max = 280.dp),
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

/** The native editable title and pencil; its spoken label remains after removing the redundant visible label. */
@Composable
private fun TitleField(title: String, onTitle: (String) -> Unit) {
    val rec = CassiniTheme.recording
    val t = CassiniTheme.type
    val focus = LocalFocusManager.current
    val description = stringResource(R.string.ui_title_label)
    // The field shows its own copy of the title and sends each change; a late state keeps the typed text.
    val text = rememberSyncedText(title)
    BasicTextField(
        value = text.value,
        onValueChange = { text.edit(it, TITLE_MAX_LENGTH, onTitle) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 18.dp, end = 4.dp)
            .semantics { contentDescription = description },
        textStyle = t.screenTitle.copy(fontSize = 20.sp, color = rec.text),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        singleLine = true,
        cursorBrush = SolidColor(rec.status),
        decorationBox = { inner ->
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
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
