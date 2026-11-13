package io.codemyriad.cassini.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.theme.CassiniTypography
import io.codemyriad.cassini.ui.theme.mono
import io.codemyriad.cassini.ui.theme.sans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** System type, without bundled or web-font fallbacks (L-13). */
class FontAssetsTest {
    private val root = File(requireNotNull(System.getProperty("cassini.repoRoot")))

    @Test fun systemSansAndTabularTimeStylesShareTheNativeFamily() {
        assertEquals(17.sp, CassiniTypography.meetingTitle.fontSize)
        assertEquals(13.sp, CassiniTypography.caption.fontSize)
        assertEquals(FontFamily.Default, sans(17).fontFamily)
        assertEquals(FontFamily.Default, mono(13).fontFamily)
        val styles = listOf(
            CassiniTypography.timer,
            CassiniTypography.monoTime,
            CassiniTypography.monoSmall,
            CassiniTypography.monoValue,
            CassiniTypography.monoSize,
        )
        for (style in styles) {
            assertEquals(FontFamily.Default, style.fontFamily)
            assertEquals("tnum", style.fontFeatureSettings)
        }
    }

    @Test fun noBundledFontResourcesOrLicencesRemain() {
        assertFalse(File(root, "ui/src/main/res/font").exists())
        assertFalse(File(root, "ui/src/main/assets/licenses/fonts").exists())
        assertFalse(File(root, "third_party/fonts").exists())
    }

    @Test fun noWebFontsUnderAnySourceSet() {
        val webFonts = root.walkTopDown()
            .onEnter { dir -> dir.name !in SKIPPED_DIRS }
            .filter { file -> file.isFile && WEB_FONT.matches(file.name) && file.isUnderSrc() }
            .map { it.relativeTo(root).path }
            .toList()
        assertEquals("Web fonts must never be committed (docs/design/ui.md section 3)", emptyList<String>(), webFonts)
    }

    private fun File.isUnderSrc(): Boolean = "src" in relativeTo(root).invariantSeparatorsPath.split('/').dropLast(1)

    private companion object {
        val WEB_FONT = Regex(".*\\.woff2?", RegexOption.IGNORE_CASE)
        val SKIPPED_DIRS = setOf(".git", ".gradle", ".kotlin", ".tools", "build", "node_modules")
    }
}
