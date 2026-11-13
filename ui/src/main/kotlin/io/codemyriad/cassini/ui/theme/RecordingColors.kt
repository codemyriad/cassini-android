@file:Suppress("MagicNumber") // The Recording palette of docs/design/ui.md section 4.1.

package io.codemyriad.cassini.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The Recording screen's colours. There is one set: the screen is dark in both themes. Banners on that screen
 * keep their light tones (`LightColors.tone(...)`).
 */
@Immutable
@Suppress("LongParameterList") // One parameter per token of the table.
class RecordingColors(
    val bg: Color,
    val text: Color,
    val text2: Color,
    /** The circle around the controls and other hairlines. */
    val line: Color,
    /** Waveform bars older than the fresh window. */
    val barIdle: Color,
    /** The newest waveform bars. */
    val barFresh: Color,
    /** The live dot next to the status. */
    val dot: Color,
    /** "Recording" / "Paused" status text. */
    val status: Color,
    /** The Pause button and the glyph on it. */
    val pauseFill: Color,
    val onPause: Color,
    /** The Stop button. */
    val finish: Color,
)

val LocalRecordingColors = staticCompositionLocalOf { CassiniRecordingColors }
