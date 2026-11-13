@file:Suppress("FunctionNaming", "MagicNumber") // Approved Android recording controls, L-48/L-49.

package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/** Stop keeps the existing finish callback; capture actions stay disabled before capture and while saving. */
@Composable
@Suppress("LongParameterList") // The existing capture state and three actions.
fun RecordingControls(
    paused: Boolean,
    onLibrary: () -> Unit,
    onPauseResume: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    captureEnabled: Boolean = true,
) {
    val rec = CassiniTheme.recording
    val onRed = CassiniTheme.colors.onRed
    val sideAxis = with(LocalDensity.current) { 26.dp.roundToPx() }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).alignBy { sideAxis }, contentAlignment = Alignment.Center) {
            RecordingSideControl(stringResource(R.string.ui_recording_library), onClick = onLibrary) {
                CassiniIcon(Glyph.List, 22.dp, 1.8f, rec.text, null)
            }
        }
        Button(
            onClick = onFinish,
            enabled = captureEnabled,
            modifier = Modifier.heightIn(min = 48.dp).alignBy { it.measuredHeight / 2 },
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = rec.finish,
                contentColor = onRed,
                disabledContainerColor = rec.finish.copy(alpha = DISABLED_ALPHA),
                disabledContentColor = onRed.copy(alpha = DISABLED_ALPHA),
            ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(18.dp).clip(CassiniTheme.shapes.small).background(LocalContentColor.current),
                )
                Text(stringResource(R.string.ui_recording_finish), style = CassiniTheme.type.label14)
            }
        }
        Box(Modifier.weight(1f).alignBy { sideAxis }, contentAlignment = Alignment.Center) {
            val label = stringResource(
                if (paused) R.string.ui_recording_resume_label else R.string.ui_recording_pause_label,
            )
            val description = stringResource(if (paused) R.string.ui_recording_resume else R.string.ui_recording_pause)
            RecordingSideControl(label, onPauseResume, captureEnabled, description) {
                if (paused) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(rec.dot))
                } else {
                    CassiniIcon(Glyph.Pause, 24.dp, 0f, rec.text, null)
                }
            }
        }
    }
}

/** A labelled outline control, with its full spoken capture action retained for Pause/Resume. */
@Composable
private fun RecordingSideControl(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String? = null,
    glyph: @Composable () -> Unit,
) {
    val rec = CassiniTheme.recording
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(52.dp).testTag("recording-side-circle").clip(CircleShape).border(1.dp, rec.line, CircleShape)
                .indication(interaction, ripple(color = rec.text)),
            contentAlignment = Alignment.Center,
        ) { glyph() }
        Text(
            label,
            style = CassiniTheme.type.caption.copy(fontSize = 12.sp),
            color = rec.text2,
            textAlign = TextAlign.Center,
        )
    }
}
