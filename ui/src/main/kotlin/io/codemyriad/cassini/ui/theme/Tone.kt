package io.codemyriad.cassini.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/** The tone of a banner or chip (docs/design/ui.md section 4.1). */
enum class Tone { Ok, Info, Warn, Error, Plain, Outline }

/**
 * The colours of a [Tone]: [bg] fill, [fg] text and icons, [dot] for the leading dot ([Color.Unspecified] for
 * [Tone.Outline], which has none) and an optional 1 dp [border].
 */
@Immutable
data class ToneColors(val bg: Color, val fg: Color, val dot: Color, val border: Color? = null)

/** The colours of [tone] in this set. The Recording screen passes [LightColors] so its banners stay light. */
fun CassiniColors.tone(tone: Tone): ToneColors = when (tone) {
    Tone.Ok -> ToneColors(bg = tealTint, fg = tealInk, dot = teal)
    Tone.Info -> ToneColors(bg = accentTint, fg = accentInk, dot = accent)
    Tone.Warn -> ToneColors(bg = amberTint, fg = amberInk, dot = amber)
    Tone.Error -> ToneColors(bg = redTint, fg = redInk, dot = error)
    Tone.Plain -> ToneColors(bg = neutralTint, fg = neutralInk, dot = neutralInk, border = line)
    Tone.Outline -> ToneColors(bg = Color.Transparent, fg = ink, dot = Color.Unspecified, border = line2)
}

/** The colours of this tone in the current theme. */
val Tone.colors: ToneColors
    @Composable @ReadOnlyComposable
    get() = LocalCassiniColors.current.tone(this)

/** The colour of a speaker (turn header dot, name label, tint, avatar). The rule that picks it is in features.md section 0. */
enum class SpeakerTone { One, Two, Three, Four, Five, Six, Unnamed }

/** A speaker's [dot], its name label and avatar initial [ink], and its [tint] (avatar fill, suggestion card). */
@Immutable
data class SpeakerColors(val dot: Color, val ink: Color, val tint: Color)

fun CassiniColors.speaker(tone: SpeakerTone): SpeakerColors {
    val (ink, wash) = when (tone) {
        SpeakerTone.One -> speaker1 to avatar1
        SpeakerTone.Two -> speaker2 to avatar2
        SpeakerTone.Three -> speaker3 to avatar3
        SpeakerTone.Four -> speaker4 to avatar4
        SpeakerTone.Five -> speaker5 to avatar5
        SpeakerTone.Six -> speaker6 to avatar6
        SpeakerTone.Unnamed -> speaker0 to avatar0
    }
    return SpeakerColors(ink, ink, wash)
}

/** The colours of this speaker tone in the current theme. */
val SpeakerTone.colors: SpeakerColors
    @Composable @ReadOnlyComposable
    get() = LocalCassiniColors.current.speaker(this)
