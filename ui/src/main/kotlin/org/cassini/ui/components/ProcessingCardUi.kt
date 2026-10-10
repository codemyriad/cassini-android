package org.cassini.ui.components

import androidx.compose.runtime.Immutable
import org.cassini.ui.banner.BannerArgs
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.format.BatteryUi
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.format.ProgressHeadline
import org.cassini.ui.format.StageUi
import org.cassini.ui.format.TimeLeftUi

/**
 * What the Transcribing card shows (ui.md section 7, `NoteMode.Processing`; features.md section 3.1). While models
 * download, [download] is set and its banner replaces the stats.
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
    val download: DownloadUi? = null,
)

/**
 * The model download inside the Transcribing card (ui.md section 7): [bytes] of [totalBytes] of [part] at [percent]
 * ("Downloading speech recognition — 46%", "296 of 640 MB."), or the check of the files when [checking]
 * ("Checking files — 80%"). [totalBytes] is an addition to ui.md: the banner body names the part's size.
 */
@Immutable
data class DownloadUi(
    val part: ModelPartUi,
    val percent: Int,
    val bytes: Long,
    val totalBytes: Long,
    val checking: Boolean = false,
) {
    /** The DL_DOWNLOADING or DL_CHECKING banner of features.md section 13. */
    fun banner(): BannerSpec =
        if (checking) {
            BannerSpec(BannerKind.DL_CHECKING, BannerArgs(percent = percent))
        } else {
            BannerSpec(
                BannerKind.DL_DOWNLOADING,
                BannerArgs(part = part, percent = percent, bytes = bytes, bytes2 = totalBytes),
            )
        }
}
