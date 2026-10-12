package org.cassini.ui.components

import org.cassini.ui.BoardTest
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.banner.boardArgs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Every banner kind in Italian (features.md section 13: English and Italian), in catalogue order and in three
 * strips: components/banners-it-{1,2,3}.png. The Italian titles and bodies are longer than the English ones; the
 * strips show that they wrap and are not cut.
 */
@Config(qualifiers = "+it-h3200dp")
class BannerItalianScreenshotTest : BoardTest() {
    override val locale: Locale = Locale.ITALIAN

    private fun strip(part: Int) = board("components", "banners-it-$part") {
        Strip {
            Padded(gap = 12) {
                for (kind in BannerKind.entries.chunked(PER_STRIP)[part - 1]) {
                    Banner(BannerSpec(kind, boardArgs(kind)), onAction = {})
                }
            }
        }
    }

    @Test fun banners1() = strip(1)

    @Test fun banners2() = strip(2)

    @Test fun banners3() = strip(3)

    @Test fun everyKindIsInAStrip() {
        assertEquals(STRIPS, BannerKind.entries.chunked(PER_STRIP).size)
    }

    private companion object {
        const val STRIPS = 3
        const val PER_STRIP = 15
    }
}
