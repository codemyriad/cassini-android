@file:Suppress("FunctionNaming") // Compose UI names.

package io.codemyriad.cassini.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.ProgressBannerCard

/** Model installs remain visible and announced even when no transcription owns them. */
@Composable
internal fun LibraryDownloadCard(progress: ProgressBannerUi, onEvent: (LibraryEvent) -> Unit, modifier: Modifier) {
    val line = when (val phase = progress.line) {
        is ProgressLine.Downloading -> phase.part.downloading(phase.percent)
        is ProgressLine.Checking -> stringResource(R.string.ui_banner_dl_checking_title, phase.percent)
        else -> return
    }
    // Reset the throttled spoken line on a stage change even if its percentage has not changed.
    key(progress.line is ProgressLine.Checking) {
        ProgressBannerCard(
            title = if (progress.title == null) {
                stringResource(R.string.ui_progress_downloading_models)
            } else {
                stringResource(R.string.ui_progress_getting_ready, progress.title)
            },
            percent = when (val phase = progress.line) {
                is ProgressLine.Downloading -> phase.percent
                is ProgressLine.Checking -> phase.percent
                else -> null
            },
            progress = progress.progress,
            line = line,
            onClick = { onEvent(LibraryEvent.OpenProgress) },
            modifier = modifier,
            onClickLabel = stringResource(
                if (progress.noteId == null) R.string.ui_action_open_settings else R.string.ui_action_open_note,
            ),
        )
    }
}
