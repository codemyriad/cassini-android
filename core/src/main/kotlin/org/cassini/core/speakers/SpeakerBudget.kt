package org.cassini.core.speakers

/** The speaker limits that several components must agree on, in one place. */
object SpeakerBudget {
    /** Speakers the Sortformer model tells apart (slots 0..7), and speakers the voice-clip cache keeps. */
    const val MAX_SPEAKERS = 8

    /** Seconds of clean clips kept per speaker for its voiceprint. */
    const val SECONDS_PER_SPEAKER = 45

    /** Turns of an earlier window are kept up to this far into the next window, which overlaps it. */
    const val TURN_OVERLAP_MS = 1000L

    /**
     * Floats a saved Sortformer streaming state may hold. The JNI side of `restoreStreamingState`
     * (scripts/streaming-diarization.patch) enforces the same bound as a literal: the patch is pinned by hash with
     * the AAR, so it does not name this constant, and SpeakerBudgetTest fails when the two differ.
     */
    const val DIARIZER_STATE_FLOATS = 200_000
}
