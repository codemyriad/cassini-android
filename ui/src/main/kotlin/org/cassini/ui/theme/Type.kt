@file:Suppress("MagicNumber") // The type scale of docs/design/ui.md section 4.3: sizes, weights and line heights.

package org.cassini.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor

/** CSS "normal" line height of Instrument Sans (hhea metrics), in em. */
const val SANS_NORMAL_LINE_HEIGHT = 1.22f

/** CSS "normal" line height of IBM Plex Mono (hhea metrics), in em. */
const val MONO_NORMAL_LINE_HEIGHT = 1.30f

/** Centred in the line box, never trimmed: the browser's half-leading model the boards were drawn with. */
private val CssLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** The hhea ascender and descender of Instrument Sans and IBM Plex Mono, in em (line gap 0 in both). */
private const val SANS_ASCENT = 0.970f
private const val SANS_DESCENT = 0.250f
private const val MONO_ASCENT = 1.025f
private const val MONO_DESCENT = 0.275f

/** The boards' pixels per CSS px: they were rendered at 2x, as the xhdpi screenshots. */
private const val BOARD_SCALE = 2f

/**
 * An Instrument Sans style of [size] sp. [lh] is the line height as a multiple of the size; it becomes an explicit
 * sp value, so `mono(13).copy(lineHeight = sans(16).lineHeight)` gives a mono run the line box of a 16 sp row.
 * [ls] is the letter spacing in em.
 *
 * Line heights are snapped to the boards' half-sp grid (whole pixels at 2x), because Android rounds every line up to
 * a whole pixel while the browser does not: unsnapped, a 16 sp line came out at 40 px instead of the board's 39 and a
 * list drifted by about 1 dp per row. The "normal" line height (the default [lh]) is the browser's: its ascent and
 * descent each rounded to whole pixels at 2x, with an exact half rounded down, as measured on the boards (a 13 px
 * chip line is 31 px, a 16 px title 39 px). So 13 sp is 15.5, 14 sp is 17 and 16 sp is 19.5. An explicit [lh] is
 * rounded to the nearest half sp (17 x 1.6 = 27.2 becomes 27).
 */
fun sans(size: Int, weight: Int = 400, lh: Float = SANS_NORMAL_LINE_HEIGHT, ls: Float = 0f): TextStyle =
    style(InstrumentSans, size, weight, lineHeight(size, lh, SANS_NORMAL_LINE_HEIGHT, SANS_ASCENT, SANS_DESCENT), ls)

/** An IBM Plex Mono style; as [sans]. Weight 600 is synthesised from the bundled 500. */
fun mono(size: Int, weight: Int = 400, lh: Float = MONO_NORMAL_LINE_HEIGHT, ls: Float = 0f): TextStyle =
    style(PlexMono, size, weight, lineHeight(size, lh, MONO_NORMAL_LINE_HEIGHT, MONO_ASCENT, MONO_DESCENT), ls)

/** The line height in sp: the browser's "normal" for [normal], else [lh] x [size] on the half-sp grid. */
@Suppress("LongParameterList") // The size, the multiple and the font's metrics.
private fun lineHeight(size: Int, lh: Float, normal: Float, ascent: Float, descent: Float): Float {
    val px = size * BOARD_SCALE
    val scaled = if (lh == normal) {
        ceil(ascent * px - 0.5f) + ceil(descent * px - 0.5f)
    } else {
        floor(lh * px + 0.5f)
    }
    return scaled / BOARD_SCALE
}

private fun style(family: FontFamily, size: Int, weight: Int, lineHeight: Float, ls: Float) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = ls.em,
    lineHeightStyle = CssLineHeight,
)

/**
 * The text styles of docs/design/ui.md section 4.3, provided by `LocalCassiniType` and read through
 * `CassiniTheme.type`. Styles carry no colour. The styles marked "uppercase" are drawn with
 * `text.uppercase(locale)` by the component; a TextStyle cannot transform case.
 */
@Immutable
class CassiniType {
    /** Recording timer, tabular figures. */
    val timer: TextStyle = mono(72, ls = -0.02f).copy(fontFeatureSettings = "tnum")

    /** Progress card percentage number. */
    val percent: TextStyle = sans(40, 600, lh = 1.0f, ls = -0.02f)

    /** The "%" span after [percent] (use `toSpanStyle()`). */
    val percentSign: TextStyle = sans(20, 600, lh = 1.0f, ls = -0.02f)

    /** Model download title. */
    val hero: TextStyle = sans(28, 600, lh = 1.15f, ls = -0.015f)

    /** "Notes". */
    val libraryTitle: TextStyle = sans(24, 600, ls = -0.01f)

    /** Settings, Saved voices and Trash titles; the Recording title field. */
    val screenTitle: TextStyle = sans(22, 600)

    /** Name sheet and dialog titles. */
    val sheetTitle: TextStyle = sans(20, 600)

    /** Note top bar and actions sheet title. */
    val noteTitle: TextStyle = sans(18, 600)

    /** Sealed transcript paragraphs. */
    val transcript: TextStyle = sans(17, lh = 1.6f)

    /** The name field. */
    val field: TextStyle = sans(17)

    /** Record. */
    val fab: TextStyle = sans(17, 600)

    /** Library rows, trash cards, voices. */
    val rowTitle: TextStyle = sans(16, 600)

    /** Search, sheet rows. */
    val body16: TextStyle = sans(16)

    /** Model download body. */
    val heroBody: TextStyle = sans(16, lh = 1.5f)

    /** Draft transcript paragraphs. */
    val draft: TextStyle = sans(16, lh = 1.55f)

    /** 56 dp primary button. */
    val cta: TextStyle = sans(16, 600)

    /** Avatar initials. */
    val avatar: TextStyle = sans(16, 700)

    /** Buttons, settings row titles, banner titles, stat values. */
    val button: TextStyle = sans(15, 600)

    /** Cancel. */
    val buttonRegular: TextStyle = sans(15)

    /** The Remember checkbox title. */
    val checkTitle: TextStyle = sans(15, 600, lh = 1.4f)

    /** Model size rows. */
    val body15: TextStyle = sans(15)

    /** Small buttons, the library banner title. */
    val label14: TextStyle = sans(14, 600)

    /** Banner body, intros, the recording caption. */
    val body14: TextStyle = sans(14, lh = 1.45f)

    /** Secondary small buttons, snackbar. */
    val body14Plain: TextStyle = sans(14)

    /** About card, Saved voices privacy note. */
    val about: TextStyle = sans(14, lh = 1.5f)

    /** Library preview and snippet. */
    val snippet: TextStyle = sans(14, lh = 1.4f)

    /** Meta and helpers. */
    val caption: TextStyle = sans(13)

    /** Status chips. */
    val chip: TextStyle = sans(13, 500)

    /** Uppercase. Library day headers; the States column headings (in text3). */
    val overline: TextStyle = sans(13, 600, ls = 0.06f)

    /** Turn header name. */
    val speaker: TextStyle = sans(13, 600)

    /** "Name", "Saved voices, closest first" (in text3). */
    val fieldLabel: TextStyle = sans(13, 600)

    /** Uppercase. Settings and sheet sections. */
    val section: TextStyle = sans(12, 600, ls = 0.06f)

    /** Progress footnote. */
    val caption12: TextStyle = sans(12, lh = 1.4f)

    /** "Likely". */
    val tag12: TextStyle = sans(12, 600)

    /** The dashed "Words may still change" tag. */
    val tag12Plain: TextStyle = sans(12)

    /** Stepper stage labels. */
    val stage: TextStyle = sans(11)

    /** The current stepper stage. */
    val stageCurrent: TextStyle = sans(11, 600)

    /** "auto". */
    val badge: TextStyle = sans(11, 500)

    /** "10" in the skip buttons: 12 sp line height. */
    val skip: TextStyle = sans(10, 700, lh = 1.2f)

    /** Durations, timestamps, ranges. */
    val monoTime: TextStyle = mono(13)

    /** Player times, format chip. */
    val monoSmall: TextStyle = mono(12)

    /** "Processed" (600 synthesised from Plex Mono 500). */
    val monoValue: TextStyle = mono(15, 600)

    /** Model sizes. */
    val monoSize: TextStyle = mono(15)
}

/** The Material [Typography] that stock components (sheets, dialogs, menus, snackbar) fall back to. */
fun CassiniType.toMaterialTypography(): Typography = Typography(
    displayLarge = timer,
    displayMedium = percent,
    displaySmall = hero,
    headlineLarge = libraryTitle,
    headlineMedium = screenTitle,
    headlineSmall = sheetTitle,
    titleLarge = noteTitle,
    titleMedium = rowTitle,
    titleSmall = button,
    bodyLarge = body16,
    bodyMedium = body14,
    bodySmall = caption,
    labelLarge = button,
    labelMedium = chip,
    labelSmall = badge,
)

/** One instance: the type scale is the same in both themes. */
val CassiniTypography: CassiniType = CassiniType()

val LocalCassiniType = staticCompositionLocalOf { CassiniTypography }
