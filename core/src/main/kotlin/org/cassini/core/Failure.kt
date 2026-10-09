package org.cassini.core

/**
 * Stable failure codes. The app maps each one to its localized message (`:app` FailureMessages.kt), the only place
 * a failure meets a string resource.
 */
enum class Failure {
    OPEN, LARGE, LONG,
    SHORT, AUDIO, WAV,
    STALLED, EMPTY, TIMINGS,
    MODEL, SPACE, DOWNLOAD,
    VERIFY, INSTALL, SAVE,
    SPEAKERS,
    MEMORY, CANCELLED, UNKNOWN,
    PLAYBACK, OPUS_ENCODER,
}

/** Keep stable failure codes separate from localized copy and native diagnostic messages. */
class UserFacingException(val failure: Failure, diagnostic: String = failure.name) : IllegalArgumentException(diagnostic)

fun requireUser(condition: Boolean, failure: Failure) {
    if (!condition) throw UserFacingException(failure)
}

/** A processing run stopped on request with its checkpoint saved. */
class ProcessingPaused : RuntimeException()
