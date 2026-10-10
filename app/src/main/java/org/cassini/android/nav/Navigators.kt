package org.cassini.android.nav

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.cassini.android.library.LibraryNavigator
import org.cassini.android.note.NoteNavigator
import org.cassini.android.recording.RecordingNavigator

// The navigators that [CassiniApp] gives each route: what a screen's "open", "close" and "replace" do to the back
// stack.

/** The Library's navigation: pushes, and the one Recording entry (pop to it when it is in the stack). */
internal fun libraryNavigator(backStack: NavBackStack<NavKey>) = object : LibraryNavigator {
    override fun open(key: NavKey) {
        if (backStack.lastOrNull() != key) backStack.add(key)
    }

    override fun openRecording() = backStack.replaceWith(DeepLinks.recording(backStack))
}

/** The Note screen's navigation: back pops it; after Move to trash only the Library stays. */
internal fun noteNavigator(backStack: NavBackStack<NavKey>) = object : NoteNavigator {
    override fun close() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    override fun closeToLibrary() = backStack.replaceWith(listOf(LibraryKey))

    override fun open(key: NavKey) {
        if (backStack.lastOrNull() != key) backStack.add(key)
    }
}

/** The Recording screen's navigation: minimise pops it; Finish puts the note (and Model download) in its place. */
internal fun recordingNavigator(backStack: NavBackStack<NavKey>) = object : RecordingNavigator {
    override fun close() {
        val index = backStack.lastIndexOf(RecordingKey)
        if (index > 0) backStack.removeAt(index)
    }

    override fun replaceWith(keys: List<NavKey>) {
        val index = backStack.lastIndexOf(RecordingKey)
        val below = if (index >= 0) backStack.take(index) else backStack.toList()
        backStack.replaceWith(DeepLinks.rooted(below) + keys)
    }
}

/** The navigation of Model download, Settings, Saved voices, Trash and Licenses: push, and back pops [own]. */
internal fun screenNavigator(backStack: NavBackStack<NavKey>, own: NavKey) = object : ScreenNavigator {
    override fun open(key: NavKey) {
        if (backStack.lastOrNull() != key) backStack.add(key)
    }

    override fun close() {
        val index = backStack.lastIndexOf(own)
        if (index > 0) backStack.removeAt(index)
    }
}
