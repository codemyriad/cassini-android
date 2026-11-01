package org.cassini.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks [Glyph] against the authoritative SVG elements in design/reference/markup: every icon of every board is a
 * glyph with the same elements and geometry, drawn at a size and stroke of [GlyphUses]; every on-board use appears
 * on a board.
 */
class GlyphMarkupTest {
    private val markupDir = File(checkNotNull(System.getProperty("cassini.repoRoot")), "design/reference/markup")

    private data class BoardIcon(
        val file: String,
        val parts: List<GlyphPart>,
        val filled: Boolean,
        val viewport: Float,
        val size: Int,
        val stroke: Float,
    )

    private val icons: List<BoardIcon> by lazy {
        val files = markupDir.listFiles { f -> f.name.endsWith(".html") }.orEmpty().sortedBy { it.name }
        check(files.size == BOARD_FILES) { "expected $BOARD_FILES boards in $markupDir, found ${files.size}" }
        files.flatMap { file ->
            SVG.findAll(file.readText()).map { parse(file.name, it.groupValues[1], it.groupValues[2]) }
        }
    }

    @Test fun everyBoardIconIsAGlyphAtAListedSize() {
        assertEquals("icons in the markup", MARKUP_ICONS, icons.size)
        val problems = icons.mapNotNull { icon ->
            val glyph = Glyph.entries.firstOrNull {
                it.parts == icon.parts && it.filled == icon.filled && it.viewport == icon.viewport
            }
            val uses = glyph?.let { GlyphUses.getValue(it) }.orEmpty()
            when {
                glyph == null -> "${icon.file}: no Glyph has ${icon.parts}"
                uses.none { it.onBoard && it.size == icon.size && it.stroke == icon.stroke } ->
                    "${icon.file}: $glyph at ${icon.size}/${icon.stroke} is not an on-board use"
                else -> null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun everyOnBoardUseIsOnABoard() {
        val missing = GlyphUses.flatMap { (glyph, uses) ->
            uses.filter { it.onBoard }.filter { use ->
                icons.none { icon ->
                    icon.parts == glyph.parts && icon.size == use.size && icon.stroke == use.stroke
                }
            }.map { "$glyph ${it.label}" }
        }
        assertTrue("on-board uses that no board shows: $missing", missing.isEmpty())
    }

    @Test fun everyGlyphHasUses() {
        assertEquals(Glyph.entries.toSet(), GlyphUses.keys)
        val noBoard = Glyph.entries.filter { glyph -> icons.none { it.parts == glyph.parts } }
        assertEquals("glyphs without a board", listOf(Glyph.ChevronUp, Glyph.Share, Glyph.Warn), noBoard)
    }

    private fun parse(file: String, attributes: String, body: String): BoardIcon {
        val svg = attrs(attributes)
        val viewBox = svg.getValue("sc-camel-view-box").split(' ').map { it.toFloat() }
        val parts = ELEMENT.findAll(body).map { element ->
            val a = attrs(element.groupValues[2])
            fun f(name: String) = a.getValue(name).toFloat()
            when (val name = element.groupValues[1]) {
                "path" -> GlyphPart.Path(a.getValue("d"))
                "circle" -> GlyphPart.Circle(f("cx"), f("cy"), f("r"))
                "rect" -> GlyphPart.Rect(f("x"), f("y"), f("width"), f("height"), f("rx"))
                "ellipse" -> {
                    val transform = checkNotNull(ROTATE.matchEntire(a.getValue("transform")))
                    val rotate = transform.groupValues.drop(1).map { it.toFloat() }
                    val pivot = rotate.drop(1)
                    assertEquals("$file: the ellipse rotates about its centre", listOf(f("cx"), f("cy")), pivot)
                    GlyphPart.Ellipse(f("cx"), f("cy"), f("rx"), f("ry"), rotate[0])
                }
                else -> error("$file: unexpected <$name> in an icon")
            }
        }.toList()
        return BoardIcon(
            file = file,
            parts = parts,
            filled = svg["fill"] != "none",
            viewport = viewBox[2],
            size = svg.getValue("width").toInt(),
            stroke = svg["stroke-width"]?.toFloat() ?: 0f,
        )
    }

    private fun attrs(text: String): Map<String, String> =
        ATTRIBUTE.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        const val BOARD_FILES = 11
        const val MARKUP_ICONS = 37
        val SVG = Regex("""<svg\b([^>]*)>(.*?)</svg>""", RegexOption.DOT_MATCHES_ALL)
        val ELEMENT = Regex("""<(\w+)\b([^>]*)>""")
        val ATTRIBUTE = Regex("""([\w-]+)="([^"]*)"""")
        val ROTATE = Regex("""rotate\(([-\d.]+) ([-\d.]+) ([-\d.]+)\)""")
    }
}
