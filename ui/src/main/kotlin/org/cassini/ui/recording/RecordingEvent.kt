package org.cassini.ui.recording

import androidx.compose.runtime.Immutable
import org.cassini.ui.banner.BannerAction as BannerButton

/** What the Recording screen asks for (features.md section 2). */
sealed interface RecordingEvent {
    @Immutable
    data class Title(val text: String) : RecordingEvent

    data object PauseResume : RecordingEvent

    data object Finish : RecordingEvent

    /** Back and the header chevron: go to the Library, the recording continues. */
    data object Minimise : RecordingEvent

    /** The Library control: the same as [Minimise]. */
    data object OpenLibrary : RecordingEvent

    /** The system settings of the app (microphone access off). */
    data object OpenSettings : RecordingEvent

    @Immutable
    data class BannerAction(val id: String, val action: BannerButton) : RecordingEvent
}
