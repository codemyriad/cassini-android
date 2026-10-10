package org.cassini.android.library

import androidx.navigation3.runtime.NavKey

/** Where the Library leads: [open] pushes a destination, [openRecording] goes to the one Recording entry. */
interface LibraryNavigator {
    fun open(key: NavKey)

    fun openRecording()
}
