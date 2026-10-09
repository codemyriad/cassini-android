// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.Counts
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Tone

/**
 * The Note screen's queued card (no board): the plain banner with "Queued" and "2 ahead of this note." ([ahead] > 0)
 * or "Starts after the current transcription.", and a small "Remove from queue" button ([onRemove]). The screen
 * gives the margins (8 top, 16 horizontal).
 */
@Composable
fun QueuedCard(ahead: Int, onRemove: () -> Unit, modifier: Modifier = Modifier, removeBusy: Boolean = false) {
    BannerCard(
        tone = Tone.Plain,
        title = stringResource(R.string.ui_note_queued),
        body = if (ahead > 0) {
            Counts.plural(R.plurals.ui_count_ahead_of_note, ahead)
        } else {
            stringResource(R.string.ui_note_queued_after_current)
        },
        modifier = modifier,
        actions = {
            SecondaryButton(
                text = stringResource(R.string.ui_action_remove_from_queue),
                onClick = onRemove,
                busy = removeBusy,
            )
        },
    )
}

@Preview
@Composable
internal fun QueuedCardPreview() {
    CassiniTheme {
        Box(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
        ) {
            QueuedCard(ahead = 2, onRemove = {})
        }
    }
}
