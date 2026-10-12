@file:Suppress("MagicNumber") // The Recording palette of docs/design/ui.md section 4.1.

package org.cassini.ui.theme

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
    /** The Finish button. */
    val finish: Color,
)

val CassiniRecordingColors: RecordingColors = RecordingColors(
    bg = Color(0xFF14171A),
    text = Color(0xFFF2F3F5),
    text2 = Color(0xFFA9B0B9),
    line = Color(0xFF3A4048),
    barIdle = Color(0xFF4A525B),
    barFresh = Color(0xFFFF8A55),
    dot = Color(0xFFFF6A2B),
    status = Color(0xFFFF9A6B),
    pauseFill = Color(0xFFF2F3F5),
    onPause = Color(0xFF14171A),
    finish = Color(0xFFC2410C),
)

val LocalRecordingColors = staticCompositionLocalOf { CassiniRecordingColors }
