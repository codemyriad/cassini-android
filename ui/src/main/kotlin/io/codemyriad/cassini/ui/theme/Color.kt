// Authored Android role aliases. Values live only in generated BrandTokenValues.kt.
@file:Suppress("MagicNumber", "MatchingDeclarationName")

package io.codemyriad.cassini.ui.theme

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
    /** Text on [accentTint], blue text buttons. */
    val accentInk: Color,
    /** Info chip and banner background. */
    val accentTint: Color,
    /** The Library banner's track. */
    val accentTrack: Color,
    /** Ok tone and privacy shield. */
    val teal: Color,
    val tealInk: Color,
    val tealTint: Color,
    /** Warn tone. */
    val amber: Color,
    val amberInk: Color,
    val amberTint: Color,
    val amberWash: Color,
    /** Speaker and saved-voice avatar colours, independent of accent and status roles (L-14/L-17 hues stay open). */
    val speakerBlue: SpeakerColors,
    val speakerTeal: SpeakerColors,
    val speakerViolet: SpeakerColors,
    val speakerAmber: SpeakerColors,
    /** Unnamed-speaker question fill; its border uses [speakerAmber] ink. */
    val speakerQuestionWash: Color,
    /** Record, error and destructive actions. */
    val red: Color,
    val redInk: Color,
    val redTint: Color,
    val redLine: Color,
    /** Text and icons on [red] (the Record button). */
    val onRed: Color,
    /** Legacy violet palette family; speaker/avatar colours have separate fields. */
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
    /** Canonical decided/placeholder roles; null until the W1 look preview activates them. */
    internal val brand: Map<String, Color>? = null,
    // Canonical roles staged for the look preview; no current component or swatch reads them in plumbing.
    val errorFill: Color = canvasValue(isDark, "error-fill"),
    val successFill: Color = canvasValue(isDark, "success-fill"),
    val speaker0: Color = canvasValue(isDark, "speaker-0"),
    val speaker1: Color = canvasValue(isDark, "speaker-1"),
    val speaker2: Color = canvasValue(isDark, "speaker-2"),
    val speaker3: Color = canvasValue(isDark, "speaker-3"),
    val speaker4: Color = canvasValue(isDark, "speaker-4"),
    val speaker5: Color = canvasValue(isDark, "speaker-5"),
    val speaker6: Color = canvasValue(isDark, "speaker-6"),
    val avatar0: Color = canvasValue(isDark, "avatar-0"),
    val avatar1: Color = canvasValue(isDark, "avatar-1"),
    val avatar2: Color = canvasValue(isDark, "avatar-2"),
    val avatar3: Color = canvasValue(isDark, "avatar-3"),
    val avatar4: Color = canvasValue(isDark, "avatar-4"),
    val avatar5: Color = canvasValue(isDark, "avatar-5"),
    val avatar6: Color = canvasValue(isDark, "avatar-6"),
)

private fun canvasValue(dark: Boolean, role: String): Color =
    (if (dark) BrandTokenValues.dark else BrandTokenValues.light).getValue(role)

// W1.1 is plumbing only: the canvas mapping is checked but the active palette stays pixel-identical.
val LightColors: CassiniColors = LegacyLightColors
val DarkColors: CassiniColors = LegacyDarkColors

// Source: codemyriad/cassini-ux@e244ad6a830e4ef77d87fb452143f7100c767139, brand/tokens.json.
// Android-only secondary roles alias published tokens; L-14/L-17 speaker hues remain frozen below.
internal val CanvasRoleTokens = mapOf(
    "errorFill" to "error-fill",
    "successFill" to "success-fill",
    "speaker0" to "speaker-0",
    "speaker1" to "speaker-1",
    "speaker2" to "speaker-2",
    "speaker3" to "speaker-3",
    "speaker4" to "speaker-4",
    "speaker5" to "speaker-5",
    "speaker6" to "speaker-6",
    "avatar0" to "avatar-0",
    "avatar1" to "avatar-1",
    "avatar2" to "avatar-2",
    "avatar3" to "avatar-3",
    "avatar4" to "avatar-4",
    "avatar5" to "avatar-5",
    "avatar6" to "avatar-6",
    "ink" to "ink",
    "bg" to "ground",
    "surface" to "surface",
    "surfaceWash" to "sunken",
    "boardBg" to "sunken",
    "text2" to "ink-2",
    "text3" to "ink-2",
    "text4" to "ink-3",
    "line" to "line",
    "line2" to "line",
    "line3" to "ink-3",
    "accent" to "accent",
    "accentFill" to "accent-strong",
    "onAccentFill" to "on-accent",
    "accentInk" to "accent-ink",
    "accentTint" to "accent-tint",
    "accentTrack" to "accent-tint",
    "teal" to "success",
    "tealInk" to "success",
    "tealTint" to "success-tint",
    "amber" to "warn-ink",
    "amberInk" to "warn-ink",
    "amberTint" to "warn-tint",
    "amberWash" to "warn-tint",
    "red" to "record",
    "redInk" to "error",
    "redTint" to "error-tint",
    "redLine" to "error",
    "onRed" to "on-record",
    "inkFill" to "ink",
    "onInkFill" to "ground",
    "snackbarBg" to "inverse",
    "snackbarText" to "on-inverse",
    "snackbarAction" to "inverse-accent",
    "scrim" to "scrim",
    "link" to "accent",
)

internal fun canvasColors(dark: Boolean): CassiniColors {
    val values = if (dark) BrandTokenValues.dark else BrandTokenValues.light
    val legacy = if (dark) LegacyDarkColors else LegacyLightColors
    fun role(name: String): Color = values.getValue(CanvasRoleTokens.getValue(name))
    return CassiniColors(
        isDark = dark,
        ink = role("ink"),
        bg = role("bg"),
        surface = role("surface"),
        surfaceWash = role("surfaceWash"),
        boardBg = role("boardBg"),
        text2 = role("text2"),
        text3 = role("text3"),
        text4 = role("text4"),
        line = role("line"),
        line2 = role("line2"),
        line3 = role("line3"),
        accent = role("accent"),
        accentFill = role("accentFill"),
        onAccentFill = role("onAccentFill"),
        accentInk = role("accentInk"),
        accentTint = role("accentTint"),
        accentTrack = role("accentTrack"),
        teal = role("teal"),
        tealInk = role("tealInk"),
        tealTint = role("tealTint"),
        amber = role("amber"),
        amberInk = role("amberInk"),
        amberTint = role("amberTint"),
        amberWash = role("amberWash"),
        red = role("red"),
        redInk = role("redInk"),
        redTint = role("redTint"),
        redLine = role("redLine"),
        onRed = role("onRed"),
        inkFill = role("inkFill"),
        onInkFill = role("onInkFill"),
        snackbarBg = role("snackbarBg"),
        snackbarText = role("snackbarText"),
        snackbarAction = role("snackbarAction"),
        scrim = role("scrim"),
        link = role("link"),
        speakerBlue = legacy.speakerBlue,
        speakerTeal = legacy.speakerTeal,
        speakerViolet = legacy.speakerViolet,
        speakerAmber = legacy.speakerAmber,
        speakerQuestionWash = legacy.speakerQuestionWash,
        violet = legacy.violet,
        violetTint = legacy.violetTint,
        brand = values,
    )
}

/** Provided by `CassiniTheme`; the default is the light set so a bare preview still renders. */
val LocalCassiniColors = staticCompositionLocalOf { LightColors }
