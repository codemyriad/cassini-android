package io.codemyriad.cassini.ui.components

import androidx.annotation.StringRes
import io.codemyriad.cassini.ui.R

/** L-26 presentation only; the app remains responsible for deriving a meeting's state. */
enum class StatusBadgeKind(@StringRes val label: Int) {
    Recording(R.string.ui_badge_recording),
    Importing(R.string.ui_preview_badge_importing),
    Saving(R.string.ui_badge_saving),
    Transcribing(R.string.ui_badge_transcribing),
    Attention(R.string.ui_badge_attention),
    Interrupted(R.string.ui_preview_badge_interrupted),
    Received(R.string.ui_badge_received),
}
