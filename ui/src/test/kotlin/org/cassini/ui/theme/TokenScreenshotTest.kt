package org.cassini.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cassini.ui.BoardTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The palette and the type scale on one strip per theme: components/tokens-light.png and tokens-dark.png. The strip
 * is taller than the phone, so the window is 3200 dp high; the capture is the content's own height.
 */
@Config(qualifiers = "+h3200dp")
class TokenScreenshotTest : BoardTest() {
    @Test fun tokensLight() = board("components", "tokens-light") { TokenStrip() }

    @Test
    @Config(qualifiers = "+night")
    fun tokensDark() = board("components", "tokens-dark", dark = true) { TokenStrip() }

    /** The strip lists every colour token and every text style: a new token must be added here too. */
    @Test fun stripCoversEveryToken() {
        val colorGetters = CassiniColors::class.java.declaredMethods.count {
            it.name.startsWith("get") && it.returnType == Long::class.javaPrimitiveType
        }
        assertEquals(colorGetters, palette(LightColors).size)
        val typeGetters = CassiniType::class.java.declaredMethods.count { it.returnType == TextStyle::class.java }
        assertEquals(typeGetters, typeScale(CassiniTypography).size)
        val recordingGetters = RecordingColors::class.java.declaredMethods.count {
            it.name.startsWith("get") && it.returnType == Long::class.javaPrimitiveType
        }
        assertEquals(recordingGetters, recordingPalette(CassiniRecordingColors).size)
    }

    @Composable
    private fun TokenStrip() {
        val c = CassiniTheme.colors
        val t = CassiniTheme.type
        Column(
            Modifier
                .width(390.dp)
                .background(c.bg)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Heading("Colours")
            Grid(palette(c), columns = 4) { (name, color) -> Swatch(name, color) }
            Heading("Tones")
            Grid(Tone.entries.toList(), columns = 3) { ToneChip(it) }
            Heading("Speakers")
            Grid(SpeakerTone.entries.toList(), columns = 2) { SpeakerChip(it) }
            Heading("Recording")
            Box(Modifier.background(CassiniTheme.recording.bg, CassiniTheme.shapes.card).padding(12.dp)) {
                Grid(recordingPalette(CassiniTheme.recording), columns = 4) { (name, color) ->
                    Swatch(name, color, ink = CassiniTheme.recording.text, meta = CassiniTheme.recording.text2)
                }
            }
            Heading("Type")
            Column(
                Modifier
                    .background(c.surface, CassiniTheme.shapes.card)
                    .border(1.dp, c.line, CassiniTheme.shapes.card)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                typeScale(t).forEach { (name, style) -> TypeRow(name, style) }
            }
        }
    }

    @Composable
    private fun Heading(text: String) {
        Text(text.uppercase(), style = CassiniTheme.type.section, color = CassiniTheme.colors.text2)
    }

    @Composable
    private fun <T> Grid(items: List<T>, columns: Int, cell: @Composable (T) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { cell(item) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun Swatch(
        name: String,
        color: Color,
        ink: Color = CassiniTheme.colors.ink,
        meta: Color = CassiniTheme.colors.text2,
    ) {
        Column {
            Box(
                Modifier
                    .height(28.dp)
                    .fillMaxWidth()
                    .background(color, CassiniTheme.shapes.small)
                    .border(1.dp, CassiniTheme.colors.line2, CassiniTheme.shapes.small),
            )
            Text(name, style = CassiniTheme.type.badge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hex(color), style = CassiniTheme.type.monoSmall, color = meta, maxLines = 1)
        }
    }

    @Composable
    private fun ToneChip(tone: Tone) {
        val colors = tone.colors
        val shape = CassiniTheme.shapes.chip
        val border = colors.border
        Row(
            Modifier
                .background(colors.bg, shape)
                .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (colors.dot != Color.Unspecified) {
                Box(Modifier.size(8.dp).background(colors.dot, CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text(tone.name, style = CassiniTheme.type.chip, color = colors.fg)
        }
    }

    @Composable
    private fun SpeakerChip(tone: SpeakerTone) {
        val colors = tone.colors
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(colors.tint, CircleShape), contentAlignment = Alignment.Center) {
                Text(tone.name.take(1), style = CassiniTheme.type.avatar, color = colors.ink)
            }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(8.dp).background(colors.dot, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text(tone.name, style = CassiniTheme.type.speaker, color = colors.ink)
        }
    }

    @Composable
    private fun TypeRow(name: String, style: TextStyle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = CassiniTheme.type.caption12,
                color = CassiniTheme.colors.text2,
                modifier = Modifier.width(92.dp),
            )
            Text(
                text = sample(name),
                style = style,
                color = CassiniTheme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }

    private fun sample(name: String): String = when (name) {
        "timer" -> "12:40"
        "percent" -> "62"
        "percentSign" -> "%"
        "skip" -> "10"
        "overline", "section" -> "Yesterday · Ieri".uppercase()
        "monoTime", "monoSmall", "monoValue", "monoSize" -> "48:13 · 640 MB"
        else -> "Product sync, giovedì"
    }

    private fun hex(color: Color): String {
        val argb = color.toArgb()
        val rgb = "#%06X".format(argb and RGB_MASK)
        val alpha = argb ushr ALPHA_SHIFT
        return if (alpha == OPAQUE) rgb else "$rgb·${alpha * PERCENT / OPAQUE}%"
    }

    private companion object {
        const val RGB_MASK = 0xFFFFFF
        const val ALPHA_SHIFT = 24
        const val OPAQUE = 255
        const val PERCENT = 100

        fun palette(c: CassiniColors): List<Pair<String, Color>> = listOf(
            "ink" to c.ink,
            "bg" to c.bg,
            "surface" to c.surface,
            "surfaceWash" to c.surfaceWash,
            "boardBg" to c.boardBg,
            "text2" to c.text2,
            "text3" to c.text3,
            "text4" to c.text4,
            "line" to c.line,
            "line2" to c.line2,
            "line3" to c.line3,
            "accent" to c.accent,
            "accentFill" to c.accentFill,
            "onAccentFill" to c.onAccentFill,
            "accentInk" to c.accentInk,
            "accentTint" to c.accentTint,
            "accentTrack" to c.accentTrack,
            "teal" to c.teal,
            "tealInk" to c.tealInk,
            "tealTint" to c.tealTint,
            "amber" to c.amber,
            "amberInk" to c.amberInk,
            "amberTint" to c.amberTint,
            "amberWash" to c.amberWash,
            "red" to c.red,
            "redInk" to c.redInk,
            "redTint" to c.redTint,
            "redLine" to c.redLine,
            "onRed" to c.onRed,
            "violet" to c.violet,
            "violetTint" to c.violetTint,
            "inkFill" to c.inkFill,
            "onInkFill" to c.onInkFill,
            "snackbarBg" to c.snackbarBg,
            "snackbarText" to c.snackbarText,
            "snackbarAction" to c.snackbarAction,
            "scrim" to c.scrim,
            "link" to c.link,
        )

        fun recordingPalette(r: RecordingColors): List<Pair<String, Color>> = listOf(
            "bg" to r.bg,
            "text" to r.text,
            "text2" to r.text2,
            "line" to r.line,
            "barIdle" to r.barIdle,
            "barFresh" to r.barFresh,
            "dot" to r.dot,
            "status" to r.status,
            "pauseFill" to r.pauseFill,
            "onPause" to r.onPause,
            "finish" to r.finish,
        )

        fun typeScale(t: CassiniType): List<Pair<String, TextStyle>> = listOf(
            "timer" to t.timer,
            "percent" to t.percent,
            "percentSign" to t.percentSign,
            "hero" to t.hero,
            "libraryTitle" to t.libraryTitle,
            "screenTitle" to t.screenTitle,
            "sheetTitle" to t.sheetTitle,
            "noteTitle" to t.noteTitle,
            "transcript" to t.transcript,
            "field" to t.field,
            "fab" to t.fab,
            "rowTitle" to t.rowTitle,
            "body16" to t.body16,
            "heroBody" to t.heroBody,
            "draft" to t.draft,
            "cta" to t.cta,
            "avatar" to t.avatar,
            "button" to t.button,
            "buttonRegular" to t.buttonRegular,
            "checkTitle" to t.checkTitle,
            "body15" to t.body15,
            "label14" to t.label14,
            "body14" to t.body14,
            "body14Plain" to t.body14Plain,
            "about" to t.about,
            "snippet" to t.snippet,
            "caption" to t.caption,
            "chip" to t.chip,
            "overline" to t.overline,
            "speaker" to t.speaker,
            "fieldLabel" to t.fieldLabel,
            "section" to t.section,
            "caption12" to t.caption12,
            "tag12" to t.tag12,
            "tag12Plain" to t.tag12Plain,
            "stage" to t.stage,
            "stageCurrent" to t.stageCurrent,
            "badge" to t.badge,
            "skip" to t.skip,
            "monoTime" to t.monoTime,
            "monoSmall" to t.monoSmall,
            "monoValue" to t.monoValue,
            "monoSize" to t.monoSize,
        )
    }
}
