package org.cassini.android.nav

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import org.cassini.android.nav.LibraryNoteScenes.isLibrary
import org.cassini.android.nav.LibraryNoteScenes.isNote

/**
 * Keeps the Library composed under a note it opened (docs/architecture.md section 6).
 *
 * Navigation 3 renders each entry through one `movableContentOf` (`SceneSetupNavEntryDecorator`). When two scenes of
 * a transition hold the same entry, `NavDisplay` renders it in the target scene only and leaves it out of the other
 * one, in the same composition that adds the target. A note opened from the Library is a [LibraryNoteScene] that
 * holds the Library under the note, so:
 * - on open, the Library's composition moves from the Library's single-pane scene into the [LibraryNoteScene];
 * - on close (back or predictive back), it moves back into the single-pane scene, the target, as the morph starts,
 *   and the exiting [LibraryNoteScene] draws the note alone over it. A cancelled gesture moves it back again.
 *
 * Each step is a move inside one composition, so the Library is never disposed and composed again from scratch.
 */
internal object LibraryNoteScenes {
    private const val ROLE = "org.cassini.android.nav.LibraryNoteScenes.role"
    private const val LIBRARY = "library"
    private const val NOTE = "note"

    /** The metadata of the Library entry. */
    fun library(): Map<String, Any> = mapOf(ROLE to LIBRARY)

    /** The metadata of a note entry. */
    fun note(): Map<String, Any> = mapOf(ROLE to NOTE)

    internal fun isLibrary(entry: NavEntry<*>) = entry.metadata[ROLE] == LIBRARY

    internal fun isNote(entry: NavEntry<*>) = entry.metadata[ROLE] == NOTE
}

/** A [LibraryNoteScene] for a stack that ends in Library, Note; the other stacks get Navigation 3's single pane. */
internal class LibraryNoteSceneStrategy : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        val note = entries.lastOrNull()?.takeIf(::isNote)
        val library = entries.getOrNull(entries.lastIndex - 1)?.takeIf(::isLibrary)
        return if (note != null && library != null) {
            LibraryNoteScene(note.contentKey, library, note, entries.dropLast(1))
        } else {
            null
        }
    }
}

/**
 * The note over the Library that opened it. Its last entry is the note, so the transitions of [appPush] and [appPop]
 * see a note scene. The Library under the note is capped at [Lifecycle.State.CREATED]: it collects no state, and its
 * back handler (which clears a search) is off. While the note covers it, the Library is not placed, so it draws
 * nothing, takes no touch and has no semantics; during the open morph it draws under the note.
 */
private data class LibraryNoteScene(
    override val key: Any,
    val library: NavEntry<NavKey>,
    val note: NavEntry<NavKey>,
    override val previousEntries: List<NavEntry<NavKey>>,
) : Scene<NavKey> {
    override val entries: List<NavEntry<NavKey>> = listOf(library, note)

    override val content: @Composable () -> Unit = {
        val hidden = LocalNavAnimatedContentScope.current.transition.coversTheLibrary()
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().hiddenUnder(hidden)) {
                val created = rememberLifecycleOwner(maxLifecycle = Lifecycle.State.CREATED)
                CompositionLocalProvider(LocalLifecycleOwner provides created) {
                    library.Content()
                }
            }
            note.Content()
        }
    }
}

/**
 * True when this scene is shown and no transition moves it, so the note covers the Library. A cancelled predictive back
 * leaves the scene with `currentState` PostExit, `targetState` Visible and nothing running; the first frame of an open
 * is PreEnter, and the Library must draw there.
 */
private fun Transition<EnterExitState>.coversTheLibrary() =
    targetState == EnterExitState.Visible && currentState != EnterExitState.PreEnter && !isRunning

/** Measures the content but does not place it while [hidden], and clears its semantics. */
private fun Modifier.hiddenUnder(hidden: Boolean): Modifier {
    val placed = layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { if (!hidden) placeable.place(0, 0) }
    }
    return if (hidden) placed.clearAndSetSemantics { } else placed
}
