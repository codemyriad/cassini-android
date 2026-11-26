// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md sections 6 and 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.testTag
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
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.components.Banner
import io.codemyriad.cassini.ui.components.IconButton44
import io.codemyriad.cassini.ui.components.RecordingControls
import io.codemyriad.cassini.ui.components.RecordingStatus
import io.codemyriad.cassini.ui.components.RecordingTimer
import io.codemyriad.cassini.ui.components.TITLE_MAX_LENGTH
import io.codemyriad.cassini.ui.components.Waveform
import io.codemyriad.cassini.ui.components.rememberSyncedText
import io.codemyriad.cassini.ui.components.screenContentWidth
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.theme.CassiniColors
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.DarkColors
import io.codemyriad.cassini.ui.theme.Glyph
import io.codemyriad.cassini.ui.theme.LightColors

/**
 * The Recording screen (board 01, features.md section 2; ui.md section 8). It is dark in both app themes: every
 * colour comes from `CassiniTheme.recording`. Banners keep their light tones, except "Microphone access is off",
 * which uses its dark tones (D104 §3, see [recordingBannerColors]).
 *
 * The editable title follows the status header inside the scrolling middle viewport. Title, banners,
 * timer, waveform and space hint scroll together so the pinned header and capture controls remain visible. Stop is the centre action,
 * All meetings is on the left and labelled Pause/Resume is on the right (L-48/L-49).
 *
 * The header chevron sends [RecordingEvent.Minimise]: the recording continues. System back is not handled here: the
 * navigation in :app pops `RecordingKey` with the predictive pop of ui.md section 9, and that pop minimises too.
 * The banner's "Open settings" sends [RecordingEvent.OpenSettings]; its other actions send
 * [RecordingEvent.BannerAction].
 *
 * The status shows only while the capture runs or is paused. While the capture starts and while it is saved, the
 * caption tells why ("Starting the microphone…", "Saving the recording…") and Pause and Stop are disabled. While the
 * microphone is refused or asked for there is no caption: the "Microphone access is off" notice says it (D104 §3).
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
        RecordingMiddle(state, onEvent, Modifier.weight(1f).fillMaxWidth())
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

/** Title, alerts and meter share the bounded space between the pinned header and controls. */
@Composable
private fun RecordingMiddle(state: RecordingUiState, onEvent: (RecordingEvent) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val viewportHeight = maxHeight
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            RecordingTitleAndBanner(state, onEvent)
            RecordingMeter(state)
            Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
private fun RecordingTitleAndBanner(state: RecordingUiState, onEvent: (RecordingEvent) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
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
                colors = recordingBannerColors(spec.kind),
            )
        }
    }
}

/** Symmetric padding supplies a scrolling gap without moving the centred meter on tall windows. */
@Composable
private fun RecordingMeter(state: RecordingUiState) {
    val rec = CassiniTheme.recording
    Column(
        Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RecordingTimer(state.elapsedMs)
        Waveform(state.levels, running = state.phase == RecordingPhase.Recording)
        caption(state)?.let { text ->
            Text(
                text = text,
                modifier = Modifier.widthIn(max = 280.dp),
                style = CassiniTheme.type.body14,
                color = rec.text2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The palette a banner on the always-dark Recording screen is drawn with. "Microphone access is off" uses its dark
 * tones (D104 §3, strip 7: dark red wash, red title, light body, light Open settings pill); the other banners keep
 * their light tones.
 */
internal fun recordingBannerColors(kind: BannerKind): CassiniColors =
    if (kind == BannerKind.REC_MIC_OFF) DarkColors else LightColors

/**
 * The header row, 48 dp: Minimise (a 44 dp visual; the 48 dp layout overlaps 2 dp on each side, so the space
 * between the items is the board's) and the status, centred. There is no format badge (QA 25 Nov: "Opus 48 kHz" lives
 * in Document info, as on iPhone); an empty 44 dp slot balances Minimise.
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
        Spacer(Modifier.width(44.dp))
    }
}

/**
 * The native editable title and pencil; its spoken label remains after removing the redundant visible label. An empty
 * title shows "Add a title" (QA 25 Nov).
 */
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
                    ) {
                        if (text.value.text.isEmpty()) {
                            Text(
                                stringResource(R.string.ui_recording_title_placeholder),
                                style = t.screenTitle.copy(fontSize = 20.sp),
                                color = rec.text2,
                                maxLines = 1,
                                modifier = Modifier.testTag("recording-title-placeholder"),
                            )
                        }
                        inner()
                    }
                    CassiniIcon(Glyph.Pencil, 18.dp, 1.8f, rec.text2, null)
                }
            }
        },
    )
}

/**
 * The caption under the waveform: the hint while capturing, else why nothing is captured yet. None while the
 * microphone is refused or asked for: the "Microphone access is off" notice already says it (D104 §3).
 */
@Composable
private fun caption(state: RecordingUiState): String? = when (state.phase) {
    RecordingPhase.NeedsPermission -> null
    RecordingPhase.Starting -> stringResource(R.string.ui_recording_starting)
    RecordingPhase.Stopping -> stringResource(R.string.ui_recording_stopping)
    RecordingPhase.Recording, RecordingPhase.Paused -> when (val hint = state.hint) {
        is RecordingHint.Space -> Durations.spaceHint(hint.ms)
        null -> stringResource(R.string.ui_recording_hint)
    }
}
