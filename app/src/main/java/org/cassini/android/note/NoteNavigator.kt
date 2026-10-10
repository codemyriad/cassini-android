package org.cassini.android.note

import androidx.navigation3.runtime.NavKey

/** Where the Note screen leads. */
interface NoteNavigator {
    /** Back: pops the Note screen. */
    fun close()

    /** After Move to trash: back to the Library (the entries above it go). */
    fun closeToLibrary()

    fun open(key: NavKey)
}
