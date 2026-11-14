package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.format.Dates
import io.codemyriad.cassini.ui.format.Durations

/**
 * "Today, 10:02 · 48:13", then the catalog's provenance words of an imported note ("· Imported"; L-10, D99). The
 * provenance suffix is generated from `format.previewSubtitle.received` / `.importedOtherDevice`, so a "Date
 * unknown" label keeps it verbatim, as on GNOME.
 */
@Composable
@ReadOnlyComposable
internal fun subtitleText(subtitle: NoteSubtitleUi): String {
    val plain = stringResource(
        R.string.ui_join_dot,
        subtitle.recordedLabel ?: Dates.dayTime(subtitle.day, subtitle.time),
        Durations.clock(subtitle.durationMs),
    )
    val provenance = when (subtitle.provenance) {
        null -> return plain
        ProvenanceUi.IMPORTED -> stringResource(R.string.ui_subtitle_imported)
        ProvenanceUi.IMPORTED_OTHER_DEVICE -> stringResource(R.string.ui_subtitle_imported_other_device)
    }
    return stringResource(R.string.ui_join_dot, plain, provenance)
}
