package io.codemyriad.cassini.data.models

import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.models.ModelPart

/** What Settings and the Model download screen show for one [ModelPart] (features.md sections 5 and 11). */
sealed interface ModelStatus {
    data object Ready : ModelStatus

    /** Not installed. [consented]: "Downloads with next transcription"; otherwise "Not downloaded". */
    data class Missing(val consented: Boolean) : ModelStatus

    /** [bytes] of the part's [total] download bytes are here. */
    data class Downloading(val percent: Int, val bytes: Long, val total: Long) : ModelStatus

    /** "Checking files": the downloaded files are being verified. */
    data class Verifying(val percent: Int) : ModelStatus

    /** The last install of this part failed; it tries again next time. [detail] is a diagnostic, never shown. */
    data class Failed(val failure: Failure, val detail: String?) : ModelStatus
}
