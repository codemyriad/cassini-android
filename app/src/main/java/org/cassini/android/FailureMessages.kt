package org.cassini.android

import org.cassini.core.Failure

/** The localized message for each failure code: the only place a [Failure] meets a string resource. */
val Failure.stringRes: Int
    get() = when (this) {
        Failure.OPEN -> R.string.error_open
        Failure.LARGE -> R.string.error_large
        Failure.LONG -> R.string.error_long
        Failure.SHORT -> R.string.error_short
        Failure.AUDIO -> R.string.error_audio
        Failure.WAV -> R.string.error_wav
        Failure.STALLED -> R.string.error_stalled
        Failure.EMPTY -> R.string.error_empty
        Failure.TIMINGS -> R.string.error_timings
        Failure.MODEL -> R.string.error_model
        Failure.SPACE -> R.string.error_space
        Failure.DOWNLOAD -> R.string.error_download
        Failure.VERIFY -> R.string.error_verify
        Failure.INSTALL -> R.string.error_install
        Failure.SAVE -> R.string.error_save
        Failure.SPEAKERS -> R.string.error_speakers
        Failure.MEMORY -> R.string.error_memory
        Failure.CANCELLED -> R.string.error_cancelled
        Failure.UNKNOWN -> R.string.error_unknown
        Failure.PLAYBACK -> R.string.error_playback
        Failure.OPUS_ENCODER -> R.string.error_opus_encoder
    }
