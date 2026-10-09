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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
 * after it, "Recording" (14/600 rec.status) or "Paused" (rec.text2). The word carries the state; a polite live
 * region announces the change.
 */
@Composable
fun RecordingStatus(paused: Boolean, modifier: Modifier = Modifier) {
    val rec = CassiniTheme.recording
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordingDot(paused)
        val label = if (paused) R.string.ui_recording_status_paused else R.string.ui_recording_status_recording
        Text(
            text = stringResource(label),
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
 */
@Composable
fun RecordingControls(
    paused: Boolean,
    onLibrary: () -> Unit,
    onPauseResume: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
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
                        .indication(interaction, ripple()),
                    contentAlignment = Alignment.Center,
                ) {
                    CassiniIcon(Glyph.List, 22.dp, 1.8f, rec.text, null)
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            PauseResumeButton(paused, onPauseResume)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LabelledCircle(stringResource(R.string.ui_recording_finish), onFinish) { interaction ->
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(rec.finish)
                        .indication(interaction, ripple()),
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
    circle: @Composable (MutableInteractionSource) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        circle(interaction)
        Text(text = label, style = CassiniTheme.type.caption, color = CassiniTheme.recording.text)
    }
}

@Composable
private fun PauseResumeButton(paused: Boolean, onClick: () -> Unit) {
    val rec = CassiniTheme.recording
    val description = stringResource(if (paused) R.string.ui_recording_resume else R.string.ui_recording_pause)
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(rec.pauseFill)
            .clickable(role = Role.Button, onClick = onClick)
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
