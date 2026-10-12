package org.cassini.ui.models

import androidx.compose.runtime.Immutable
import org.cassini.ui.banner.BannerAction as BannerButton

/** What the Model download screen asks for (features.md section 5). */
sealed interface ModelDownloadEvent {
    data object Close : ModelDownloadEvent

    /** "Download and transcribe" or "Download". */
    data object Download : ModelDownloadEvent

    /** "Not now — keep the audio". */
    data object NotNow : ModelDownloadEvent

    /** "Where the models come from · Privacy": open Licenses. */
    data object OpenSources : ModelDownloadEvent

    @Immutable
    data class BannerAction(val id: String, val action: BannerButton) : ModelDownloadEvent
}
