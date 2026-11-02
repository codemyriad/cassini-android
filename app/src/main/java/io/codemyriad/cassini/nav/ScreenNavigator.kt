package io.codemyriad.cassini.nav

import androidx.navigation3.runtime.NavKey

/** Where Model download, Settings, Saved voices, Trash and Licenses lead: [open] pushes, [close] pops the screen. */
interface ScreenNavigator {
    fun open(key: NavKey)

    fun close()
}
