// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.Durations
import org.cassini.ui.format.Spoken
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/**
 * The Recording timer (board 01): `timer` (72 sp mono, tabular) in rec.text, centred. Its description is spoken
 * ("24 minutes 7 seconds"); it is not a live region, so TalkBack does not read every second.
 */
@Composable
fun RecordingTimer(elapsedMs: Long, modifier: Modifier = Modifier) {
    val description = Spoken.duration(elapsedMs)
    Text(
        text = Durations.clock(elapsedMs),
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        style = CassiniTheme.type.timer,
        color = CassiniTheme.recording.text,
        textAlign = TextAlign.Center,
    )
}

/**
 * The Recording header status (board 01): the 10 dp live dot (paused: a hollow ring, 1.5 dp rec.text2) and, 8 dp
 * after it, "Recording" (14/600 rec.status) or "Paused" (rec.text2). TalkBack reads "Recording status" with the
 * word as its state description; a polite live region announces the change.
 */
@Composable
fun RecordingStatus(paused: Boolean, modifier: Modifier = Modifier) {
    val rec = CassiniTheme.recording
    val label = stringResource(R.string.ui_recording_status_label)
    val state = stringResource(
        if (paused) R.string.ui_recording_status_paused else R.string.ui_recording_status_recording,
    )
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = state
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordingDot(paused)
        Text(
            text = state,
            style = CassiniTheme.type.label14,
            color = if (paused) rec.text2 else rec.status,
        )
    }
}

/**
 * The Recording controls (board 01), three equal columns: "Library" ([onLibrary]: a 56 dp circle with a 1 dp rec.line
 * border and List 22/1.8, the label 13 sp 6 dp below), Pause/Resume ([onPauseResume]: an 88 dp rec.pauseFill circle
 * with Pause 30, or a 26 dp rec.dot disc when [paused]; "Pause recording" / "Resume recording") and "Finish"
 * ([onFinish]: a 56 dp rec.finish circle with an 18 dp white square, radius 4).
 *
 * When [captureEnabled] is false (no capture yet, or it is being saved), Pause/Resume and Finish are disabled at
 * 38 %; Library stays enabled.
 */
@Composable
@Suppress("LongParameterList") // The three controls, their state and the modifier.
fun RecordingControls(
    paused: Boolean,
    onLibrary: () -> Unit,
    onPauseResume: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    captureEnabled: Boolean = true,
) {
    val rec = CassiniTheme.recording
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LabelledCircle(stringResource(R.string.ui_recording_library), onLibrary) { interaction ->
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .border(1.dp, rec.line, CircleShape)
                        .indication(interaction, ripple(color = rec.text)),
                    contentAlignment = Alignment.Center,
                ) {
                    CassiniIcon(Glyph.List, 22.dp, 1.8f, rec.text, null)
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            PauseResumeButton(paused, onPauseResume, captureEnabled)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LabelledCircle(stringResource(R.string.ui_recording_finish), onFinish, captureEnabled) { interaction ->
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(rec.finish)
                        .indication(interaction, ripple(color = rec.text)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CassiniTheme.shapes.small)
                            .background(CassiniTheme.colors.onRed),
                    )
                }
            }
        }
    }
}

/** A round control with its [label] 6 dp below (13 sp rec.text); the whole column is the button. */
@Composable
private fun LabelledCircle(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    circle: @Composable (MutableInteractionSource) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        circle(interaction)
        Text(text = label, style = CassiniTheme.type.caption, color = CassiniTheme.recording.text)
    }
}

@Composable
private fun PauseResumeButton(paused: Boolean, onClick: () -> Unit, enabled: Boolean) {
    val rec = CassiniTheme.recording
    val description = stringResource(if (paused) R.string.ui_recording_resume else R.string.ui_recording_pause)
    Box(
        modifier = Modifier
            .size(88.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .background(rec.pauseFill)
            .clickable(
                interactionSource = null,
                indication = ripple(color = rec.onPause),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (paused) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(rec.dot),
            )
        } else {
            CassiniIcon(Glyph.Pause, 30.dp, 0f, rec.onPause, null)
        }
    }
}

@Preview
@Composable
internal fun RecordingWidgetsPreview() {
    CassiniTheme {
        Column(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.recording.bg)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            RecordingStatus(paused = false)
            RecordingTimer(1_447_000)
            RecordingControls(paused = false, onLibrary = {}, onPauseResume = {}, onFinish = {})
        }
    }
}
