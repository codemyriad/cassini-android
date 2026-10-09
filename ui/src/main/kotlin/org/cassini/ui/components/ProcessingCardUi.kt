package org.cassini.ui.components

import androidx.compose.runtime.Immutable
import org.cassini.ui.format.BatteryUi
import org.cassini.ui.format.ProgressHeadline
import org.cassini.ui.format.StageUi
import org.cassini.ui.format.TimeLeftUi

/**
 * What the Transcribing card shows (ui.md section 7, `NoteMode.Processing`; features.md section 3.1). The download
 * phase is not a field: the screen passes the download banner to [ProcessingCard] in place of the stats.
 *
 * [rangeStartMs] to [rangeEndMs] is the window being transcribed ("29:00–29:45"; hidden when both are 0). [percent]
 * is 0–99 until done. [skippedMatching] marks the Matching stage as skipped (no saved voices to match). [doneMs] of
 * [totalMs] is the Processed stat; [words] the words found so far; [lowMemory] shows the amber memory hint.
 */
@Immutable
data class ProcessingCardUi(
    val headline: ProgressHeadline,
    val rangeStartMs: Long,
    val rangeEndMs: Long,
    val percent: Int,
    val stage: StageUi,
    val skippedMatching: Boolean,
    val timeLeft: TimeLeftUi,
    val doneMs: Long,
    val totalMs: Long,
    val battery: BatteryUi,
    val words: Int,
    val lowMemory: Boolean,
)
