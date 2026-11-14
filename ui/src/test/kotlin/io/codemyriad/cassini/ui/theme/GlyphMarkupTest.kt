package io.codemyriad.cassini.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks [Glyph] against the authoritative SVG elements in design/reference/markup: every icon of every board is a
 * glyph with the same elements and geometry, drawn at a size and stroke of [GlyphUses]; every on-board use appears
 * on a board. The boards' line-art logo (a circle and a rotated ellipse) is retired: L-04 replaces it with the
 * cassini.talk Saturn image (saturn-nav in the Library search field, saturn-mid on Model download), so it is no glyph.
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

    private val svgs: List<Triple<String, String, String>> by lazy {
        val files = markupDir.listFiles { f -> f.name.endsWith(".html") }.orEmpty().sortedBy { it.name }
        check(files.size == BOARD_FILES) { "expected $BOARD_FILES boards in $markupDir, found ${files.size}" }
        files.flatMap { file ->
            SVG.findAll(file.readText()).map { Triple(file.name, it.groupValues[1], it.groupValues[2]) }
        }
    }

    private val icons: List<BoardIcon> by lazy {
        svgs.filterNot { (_, _, body) -> RETIRED_LOGO in body }.map { (file, attributes, body) ->
            parse(file, attributes, body)
        }
    }

    @Test fun onlyTheRetiredLogoIsSkipped() {
        val logos = svgs.filter { (_, _, body) -> RETIRED_LOGO in body }.map { it.first }
        assertEquals(listOf("00-Library.html", "05-Model_download.html"), logos)
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
        const val MARKUP_ICONS = 35
        const val RETIRED_LOGO = "<ellipse"
        val SVG = Regex("""<svg\b([^>]*)>(.*?)</svg>""", RegexOption.DOT_MATCHES_ALL)
        val ELEMENT = Regex("""<(\w+)\b([^>]*)>""")
        val ATTRIBUTE = Regex("""([\w-]+)="([^"]*)"""")
    }
}
