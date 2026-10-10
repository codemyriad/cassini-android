package org.cassini.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.cassini.ui.BoardTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vectors, the cache and the semantics of [CassiniIcon]. */
class CassiniIconTest : BoardTest() {
    @Test fun everyUseBuildsOneVectorPathPerPart() {
        val uses = GlyphUses.flatMap { (glyph, uses) -> uses.map { glyph to it } }
        uses.forEach { (glyph, use) ->
            val vector = CassiniIcons.vector(glyph, use.stroke)
            assertEquals(glyph.viewport, vector.viewportWidth)
            val paths = vector.root.paths()
            assertEquals("$glyph: one path per SVG element", glyph.parts.size, paths.size)
            assertTrue("$glyph: fill only when filled", paths.all { (it.fill != null) == glyph.filled })
            assertTrue("$glyph: stroke only when stroked", paths.all { (it.stroke != null) != glyph.filled })
            val widths = paths.map { it.strokeLineWidth }.toSet()
            assertEquals(glyph.name, setOf(if (glyph.filled) 0f else use.stroke), widths)
        }
    }

    @Test fun theLogoEllipseIsRotatedAboutTheCentre() {
        val group = CassiniIcons.vector(Glyph.Logo, 1.6f).root.filterIsInstance<VectorGroup>().single()
        assertEquals(-20f, group.rotation)
        assertEquals(14f, group.pivotX)
        assertEquals(14f, group.pivotY)
    }

    @Test fun theCacheIsKeyedByGlyphAndStroke() {
        assertSame(CassiniIcons.vector(Glyph.Trash, 1.7f), CassiniIcons.vector(Glyph.Trash, 1.7f))
        assertNotSame(CassiniIcons.vector(Glyph.Trash, 1.7f), CassiniIcons.vector(Glyph.Trash, 1.8f))
        assertSame(CassiniIcons.vector(Glyph.More, 0f), CassiniIcons.vector(Glyph.More, 1.8f))
        assertSame(Glyph.Fwd10, Glyph.Retranscribe)
        assertThrows(IllegalArgumentException::class.java) { CassiniIcons.vector(Glyph.Trash, 0f) }
    }

    @Test fun describedIconsAreImagesAndDecorativeOnesAreSilent() {
        show {
            Row {
                CassiniIcon(Glyph.Trash, 22.dp, 1.7f, CassiniTheme.colors.ink, contentDescription = "Trash")
                CassiniIcon(Glyph.Settings, 22.dp, 1.7f, CassiniTheme.colors.ink, contentDescription = null)
            }
        }
        rule.onNodeWithContentDescription("Trash")
            .assertWidthIsEqualTo(22.dp)
            .assertHeightIsEqualTo(22.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEquals(1)
    }

    private fun VectorGroup.paths(): List<VectorPath> = flatMap {
        when (it) {
            is VectorPath -> listOf(it)
            is VectorGroup -> it.paths()
        }
    }
}
