// This file is the palette: every hex value of docs/design/ui.md section 4.1 lives here. Its name is the one
// docs/design/ui.md section 1 gives.
@file:Suppress("MagicNumber", "MatchingDeclarationName")

package org.cassini.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colour tokens of docs/design/ui.md section 4.1. Screens and components read them through
 * `CassiniTheme.colors` and never use a raw hex value. [LightColors] and [DarkColors] are the two sets.
 */
@Immutable
@Suppress("LongParameterList") // One parameter per token of the table.
class CassiniColors(
    /** True for the dark set. Board frames and window-level code use it to pick the matching resources. */
    val isDark: Boolean,
    /** Primary text. */
    val ink: Color,
    /** Screen background; also the window background `ui_window_bg`. */
    val bg: Color,
    /** Cards, sheets, the transcript pane and the player. */
    val surface: Color,
    /** Highlighted sheet rows ("Cassini document", "Likely"). */
    val surfaceWash: Color,
    /** The States board background and the inactive slider track. */
    val boardBg: Color,
    /** Meta text, captions, icons and section headers. */
    val text2: Color,
    /** Card bodies, placeholder-speaker paragraphs, the settled draft and the States column headings. */
    val text3: Color,
    /** Unsettled draft words and placeholders. Light is #687180, not the board's #6B7480 (fails AA). */
    val text4: Color,
    /** Hairlines and card borders. */
    val line: Color,
    /** Outline buttons and chips, the drag handle. */
    val line2: Color,
    /** The dashed "Words may still change" tag and the plain-banner dot. */
    val line3: Color,
    /** Progress fill, slider, focused border, dots, checkbox. */
    val accent: Color,
    /** Primary buttons and the active word. */
    val accentFill: Color,
    /** Text and icons on [accentFill]. */
    val onAccentFill: Color,
    /** Text on [accentTint], blue text buttons, blue avatar initials. */
    val accentInk: Color,
    /** Info chip and banner background. */
    val accentTint: Color,
    /** The Library banner's track. */
    val accentTrack: Color,
    /** Ok tone and privacy shield. */
    val teal: Color,
    val tealInk: Color,
    val tealTint: Color,
    /** Warn tone and unnamed speakers. */
    val amber: Color,
    val amberInk: Color,
    val amberTint: Color,
    val amberWash: Color,
    /** Record, error and destructive actions. */
    val red: Color,
    val redInk: Color,
    val redTint: Color,
    val redLine: Color,
    /** Text and icons on [red] (the Record button). */
    val onRed: Color,
    /** The third named-speaker tint and the fourth avatar tint. */
    val violet: Color,
    val violetTint: Color,
    /** Ink pills, play/pause and the selected segment. */
    val inkFill: Color,
    val onInkFill: Color,
    val snackbarBg: Color,
    val snackbarText: Color,
    val snackbarAction: Color,
    /** Behind sheets and dialogs, with its alpha. Over [bg] it gives the boards' #8A9098 in light. */
    val scrim: Color,
    /** Inline links (underlined). */
    val link: Color,
)

/** The light set (the boards). */
val LightColors: CassiniColors = CassiniColors(
    isDark = false,
    ink = Color(0xFF14171A),
    bg = Color(0xFFF5F6F7),
    surface = Color(0xFFFFFFFF),
    surfaceWash = Color(0xFFF5F6F7),
    boardBg = Color(0xFFECEEF0),
    text2 = Color(0xFF4A525B),
    text3 = Color(0xFF3A4250),
    text4 = Color(0xFF687180),
    line = Color(0xFFE2E5E9),
    line2 = Color(0xFFC9CED5),
    line3 = Color(0xFF9AA2AC),
    accent = Color(0xFF2346C8),
    accentFill = Color(0xFF2346C8),
    onAccentFill = Color(0xFFFFFFFF),
    accentInk = Color(0xFF1A3596),
    accentTint = Color(0xFFE8EDFC),
    accentTrack = Color(0xFFC9D4F6),
    teal = Color(0xFF0F766E),
    tealInk = Color(0xFF0B5A54),
    tealTint = Color(0xFFE3F2EF),
    amber = Color(0xFFB45309),
    amberInk = Color(0xFF8A3B07),
    amberTint = Color(0xFFFCEFE3),
    amberWash = Color(0xFFFCF6EE),
    red = Color(0xFFC2410C),
    redInk = Color(0xFFA3330B),
    redTint = Color(0xFFFBE7E1),
    redLine = Color(0xFFE3B9A6),
    onRed = Color(0xFFFFFFFF),
    violet = Color(0xFF5B2BB5),
    violetTint = Color(0xFFEFEAFB),
    inkFill = Color(0xFF14171A),
    onInkFill = Color(0xFFFFFFFF),
    snackbarBg = Color(0xFF14171A),
    snackbarText = Color(0xFFF2F3F5),
    snackbarAction = Color(0xFF9DB4FF),
    scrim = Color(0xFF1F2A39).copy(alpha = 0.5f),
    link = Color(0xFF2346C8),
)

/** The dark set (no board: it follows docs/design/ui.md and waits for the owner's review). */
val DarkColors: CassiniColors = CassiniColors(
    isDark = true,
    ink = Color(0xFFECEEF1),
    bg = Color(0xFF0F1214),
    surface = Color(0xFF181C20),
    surfaceWash = Color(0xFF1F2429),
    boardBg = Color(0xFF1F2429),
    text2 = Color(0xFFA9B0B9),
    text3 = Color(0xFFC3C9D0),
    text4 = Color(0xFF8C95A0),
    line = Color(0xFF2A3036),
    line2 = Color(0xFF3A4048),
    line3 = Color(0xFF5A626C),
    accent = Color(0xFF8FA6FF),
    accentFill = Color(0xFF2346C8),
    onAccentFill = Color(0xFFFFFFFF),
    accentInk = Color(0xFFB9C7FF),
    accentTint = Color(0xFF1C2647),
    accentTrack = Color(0xFF2B3A70),
    teal = Color(0xFF4FC7B8),
    tealInk = Color(0xFF8EDDD2),
    tealTint = Color(0xFF12302D),
    amber = Color(0xFFF0A35E),
    amberInk = Color(0xFFF7C291),
    amberTint = Color(0xFF3A2614),
    amberWash = Color(0xFF2C2118),
    red = Color(0xFFC2410C),
    redInk = Color(0xFFFFB08F),
    redTint = Color(0xFF3D1E14),
    redLine = Color(0xFF6B3A2A),
    onRed = Color(0xFFFFFFFF),
    violet = Color(0xFFC1A8FF),
    violetTint = Color(0xFF2A2045),
    inkFill = Color(0xFFECEEF1),
    onInkFill = Color(0xFF0F1214),
    snackbarBg = Color(0xFFECEEF1),
    snackbarText = Color(0xFF14171A),
    snackbarAction = Color(0xFF2346C8),
    scrim = Color(0xFF000000).copy(alpha = 0.6f),
    link = Color(0xFF8FA6FF),
)

/** Provided by `CassiniTheme`; the default is the light set so a bare preview still renders. */
val LocalCassiniColors = staticCompositionLocalOf { LightColors }
