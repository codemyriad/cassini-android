package io.codemyriad.cassini.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import io.codemyriad.cassini.nav.LibraryNoteScenes.isFullScreen
import io.codemyriad.cassini.nav.LibraryNoteScenes.isLibrary
import io.codemyriad.cassini.ui.components.LocalDetailPane
import io.codemyriad.cassini.ui.theme.CassiniTheme

/**
 * The Library and a meeting side by side on a wide window (an unfolded foldable in portrait or landscape, a tablet;
 * QA 25 Nov, as on iPad): the Library stays on the left, and everything it opens shows in the right pane: the
 * meeting with Transcribing, Find, Document info and Export, and also Settings, Trash, Saved voices and the screens
 * they open. Recording stays full screen ([LibraryNoteScenes.fullScreen]), so a stack that ends in it gets the other
 * strategies. On a narrow window ([wide] false) this strategy makes no scene.
 */
internal class ListDetailSceneStrategy(private val wide: Boolean) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        val library = entries.firstOrNull()?.takeIf { wide && isLibrary(it) && !isFullScreen(entries.last()) }
        return library?.let { ListDetailScene(it, entries.last().takeIf { top -> top !== it }, entries.dropLast(1)) }
    }

    internal companion object {
        /** Material's medium width and height: a phone in landscape is not tall enough for two panes. */
        fun isWide(widthDp: Int, heightDp: Int): Boolean = widthDp >= MIN_WIDTH_DP && heightDp >= MIN_HEIGHT_DP

        /** The Library's width: 40 % of the window, 280 to 400 dp, so the meeting keeps at least a phone's width. */
        fun listWidth(window: Dp): Dp = (window * LIST_FRACTION).coerceIn(MIN_LIST, MAX_LIST)

        private const val MIN_WIDTH_DP = 600
        private const val MIN_HEIGHT_DP = 480
        private const val LIST_FRACTION = 0.4f
        private val MIN_LIST = 280.dp
        private val MAX_LIST = 400.dp
    }
}

/**
 * One scene for every stack it takes, so moving between meetings or screens in the right pane runs no screen
 * transition and the Library is never composed again. [detail] is the top entry; null shows the Library alone beside an
 * empty pane.
 */
private data class ListDetailScene(
    val library: NavEntry<NavKey>,
    val detail: NavEntry<NavKey>?,
    override val previousEntries: List<NavEntry<NavKey>>,
) : Scene<NavKey> {
    override val key: Any = ListDetailScene::class
    override val entries: List<NavEntry<NavKey>> = listOfNotNull(library, detail)

    override val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize().background(CassiniTheme.colors.bg)) {
            val listWidth = ListDetailSceneStrategy.listWidth(maxWidth)
            CompositionLocalProvider(LocalDetailPane provides PaddingValues(start = listWidth + DividerWidth)) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.width(listWidth).fillMaxHeight().testTag("list-pane")) { library.Content() }
                    VerticalDivider(thickness = DividerWidth, color = CassiniTheme.colors.line)
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("detail-pane")) { detail?.Content() }
                }
            }
        }
    }
}

private val DividerWidth = 1.dp
