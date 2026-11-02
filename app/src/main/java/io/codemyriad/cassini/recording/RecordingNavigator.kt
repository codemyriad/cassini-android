package io.codemyriad.cassini.recording

import androidx.navigation3.runtime.NavKey

/** Where the Recording screen leads: [close] minimises it; [replaceWith] puts [keys] in its place (Finish). */
interface RecordingNavigator {
    fun close()

    fun replaceWith(keys: List<NavKey>)
}
