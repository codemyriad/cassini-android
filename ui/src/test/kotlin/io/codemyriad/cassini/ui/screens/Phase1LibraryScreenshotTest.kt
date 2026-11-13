package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.fixtures.VariantFixtures
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.library.DaySection
import io.codemyriad.cassini.ui.library.LibraryScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Test
import org.robolectric.annotation.Config

/** Linux-only paired evidence for the native pinned header over scrolled Library and search content. */
class Phase1LibraryScreenshotTest : BoardTest() {
    @Test fun libraryScrolledLight() = scrolled(dark = false, search = false)

    @Test
    @Config(qualifiers = "+night")
    fun libraryScrolledDark() = scrolled(dark = true, search = false)

    @Test fun searchScrolledLight() = scrolled(dark = false, search = true)

    @Test
    @Config(qualifiers = "+night")
    fun searchScrolledDark() = scrolled(dark = true, search = true)

    private fun scrolled(dark: Boolean, search: Boolean) {
        val template = if (search) {
            VariantFixtures.library.getValue("library-search").results!!.last()
        } else {
            BoardFixtures.supplier
        }
        val rows = (0 until 20).map {
            template.copy(id = "scrolled-$it", day = if (search) DayLabel.Yesterday else null)
        }.toImmutableList()
        val state = if (search) {
            VariantFixtures.library.getValue("library-search").copy(results = rows)
        } else {
            BoardFixtures.library.copy(sections = persistentListOf(DaySection(DayLabel.Yesterday, rows)))
        }
        show(dark) { LibraryScreen(state, onEvent = {}) }
        rule.mainClock.autoAdvance = true
        val list = rule.onNodeWithTag("library-list")
        list.performScrollToIndex(6)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 48f) }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(1_000)
        capture(if (dark) "dark" else "light", if (search) "13-search-scrolled" else "12-library-scrolled")
    }
}
