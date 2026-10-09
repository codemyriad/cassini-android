package org.cassini.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The colour a use of a glyph takes, as a token. */
internal enum class IconTint {
    Ink,
    Text2,
    Accent,
    Teal,
    TealInk,
    AmberInk,
    RedInk,

    /** On the Recording screen, which is dark in both themes. */
    RecText,
    RecText2,
    ;

    val onRecording: Boolean get() = this == RecText || this == RecText2

    @Composable
    fun color(): Color {
        val c = CassiniTheme.colors
        val rec = CassiniTheme.recording
        return when (this) {
            Ink -> c.ink
            Text2 -> c.text2
            Accent -> c.accent
            Teal -> c.teal
            TealInk -> c.tealInk
            AmberInk -> c.amberInk
            RedInk -> c.redInk
            RecText -> rec.text
            RecText2 -> rec.text2
        }
    }
}

/** One size and stroke of a glyph; [onBoard] is false for the uses that only the spec gives (no board shows them). */
internal data class GlyphUse(
    val size: Int,
    val stroke: Float,
    val tint: IconTint = IconTint.Ink,
    val onBoard: Boolean = true,
) {
    val label: String get() = if (stroke == 0f) "$size" else "$size/$stroke"
}

private fun use(size: Int, stroke: Float, tint: IconTint = IconTint.Ink, onBoard: Boolean = true) =
    GlyphUse(size, stroke, tint, onBoard)

/**
 * The sizes and strokes of docs/design/ui.md section 4.5 (and the Play 14 of the FollowChip in section 6). The
 * markup test checks the on-board ones against design/reference/markup in both directions.
 */
internal val GlyphUses: Map<Glyph, List<GlyphUse>> = mapOf(
    Glyph.Logo to listOf(use(28, 1.6f), use(56, 1.3f, IconTint.Accent)),
    Glyph.Trash to listOf(use(22, 1.7f), use(24, 1.7f, IconTint.RedInk)),
    Glyph.Settings to listOf(use(22, 1.7f)),
    Glyph.Search to listOf(use(20, 1.8f, IconTint.Text2), use(21, 1.8f)),
    Glyph.Shield to listOf(use(14, 2f, IconTint.Teal), use(18, 2f, IconTint.Teal)),
    Glyph.Import to listOf(use(20, 1.8f)),
    Glyph.ChevronDown to listOf(use(22, 1.8f, IconTint.RecText), use(14, 2f, IconTint.TealInk)),
    Glyph.ChevronRight to listOf(use(18, 1.8f, IconTint.Text2)),
    Glyph.ChevronUp to listOf(use(22, 1.8f, onBoard = false)),
    Glyph.Back to listOf(use(22, 1.8f)),
    Glyph.Close to listOf(use(22, 1.8f), use(20, 1.8f, onBoard = false)),
    Glyph.Pencil to listOf(use(18, 1.8f, IconTint.RecText2), use(24, 1.7f)),
    Glyph.List to listOf(use(22, 1.8f, IconTint.RecText)),
    Glyph.Pause to listOf(use(30, 0f), use(26, 0f)),
    Glyph.Play to listOf(use(18, 0f), use(26, 0f, onBoard = false), use(14, 0f, onBoard = false)),
    Glyph.More to listOf(use(22, 0f)),
    Glyph.MoreSmall to listOf(use(20, 0f)),
    Glyph.Check to listOf(use(14, 2.2f, IconTint.TealInk)),
    Glyph.Back10 to listOf(use(28, 1.6f)),
    Glyph.Fwd10 to listOf(use(28, 1.6f), use(24, 1.7f)),
    Glyph.Music to listOf(use(24, 1.7f, IconTint.Accent)),
    Glyph.TextFile to listOf(use(24, 1.7f)),
    Glyph.Subtitles to listOf(use(24, 1.7f)),
    Glyph.Info to listOf(use(24, 1.7f)),
    Glyph.Warn to listOf(use(14, 2f, IconTint.AmberInk, onBoard = false)),
)
