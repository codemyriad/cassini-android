package org.cassini.ui.components

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * What the [Waveform] shows: [bars] are levels in 0..1, oldest first, [BAR_MS] each; the newest bar holds the
 * captured time [capturedMs]. [capturedMs] is the captured time (not the wall clock) of the newest sample, so the
 * running waveform can put the bars on a continuous time axis. `RecordingController` in `:app` uses the same
 * constants.
 */
@Immutable
data class WaveformLevels(val bars: ImmutableList<Float>, val capturedMs: Long = 0L) {
    companion object {
        /** The number of bars: the last 7 s (features.md section 2). */
        const val BARS = 56

        /** The captured time of one bar. */
        const val BAR_MS = 125L

        /** The time between two amplitude samples. */
        const val SAMPLE_MS = 100L

        /** No bars: the Waveform draws nothing. */
        val Empty = WaveformLevels(persistentListOf())
    }
}
