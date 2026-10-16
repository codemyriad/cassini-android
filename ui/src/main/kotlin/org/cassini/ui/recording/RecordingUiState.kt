package org.cassini.ui.recording

import androidx.compose.runtime.Immutable
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.components.WaveformLevels

/**
 * The Recording screen (board 01, features.md section 2; ui.md section 7). [levels] are the 56 waveform bars
 * (0–1, oldest first) with the captured time of their newest sample; [format] and [sampleRateHz] make the format
 * chip ("Opus 48 kHz"); [hint] follows the fixed hint sentence; [banner] sits below the title (light tones on the
 * dark surface).
 *
 * [title] is the text of a field. The field keeps its own copy and sends each change as [RecordingEvent.Title]; it
 * takes a different text from the state only when that text is not one it sent (SyncedText). Thus the
 * ViewModel can update this value through its StateFlow.
 */
@Immutable
data class RecordingUiState(
    val phase: RecordingPhase,
    val title: String,
    val elapsedMs: Long,
    val levels: WaveformLevels = WaveformLevels.Empty,
    val format: CaptureFormat,
    val sampleRateHz: Int,
    val hint: RecordingHint? = null,
    val banner: BannerSpec? = null,
)

/** Where the capture is. */
enum class RecordingPhase { NeedsPermission, Starting, Recording, Paused, Stopping }

/** The capture codec of the format chip. */
enum class CaptureFormat { OPUS, M4A }

/** The second sentence of the hint (`RecordingSpace`). A recording has no time limit, so it tells only the space. */
sealed interface RecordingHint {
    /** "Space for about 31 h more." */
    @Immutable
    data class Space(val ms: Long) : RecordingHint
}
