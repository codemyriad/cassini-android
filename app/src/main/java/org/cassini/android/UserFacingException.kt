package org.cassini.android

enum class Failure(val stringRes: Int) {
    OPEN(R.string.error_open), LARGE(R.string.error_large), LONG(R.string.error_long),
    SHORT(R.string.error_short), AUDIO(R.string.error_audio), WAV(R.string.error_wav),
    STALLED(R.string.error_stalled), EMPTY(R.string.error_empty), TIMINGS(R.string.error_timings),
    MODEL(R.string.error_model), SPACE(R.string.error_space), DOWNLOAD(R.string.error_download),
    VERIFY(R.string.error_verify), INSTALL(R.string.error_install), SAVE(R.string.error_save),
    SPEAKERS(R.string.error_speakers),
    MEMORY(R.string.error_memory), CANCELLED(R.string.error_cancelled), UNKNOWN(R.string.error_unknown),
    PLAYBACK(R.string.error_playback), OPUS_ENCODER(R.string.error_opus_encoder),
    TIME_LIMIT(R.string.error_time_limit),
}

/** Keep stable failure codes separate from localized copy and native diagnostic messages. */
class UserFacingException(val failure: Failure, diagnostic: String = failure.name) : IllegalArgumentException(diagnostic)

fun requireUser(condition: Boolean, failure: Failure) {
    if (!condition) throw UserFacingException(failure)
}
