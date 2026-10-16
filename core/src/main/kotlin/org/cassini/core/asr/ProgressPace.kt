package org.cassini.core.asr

/**
 * Where the progress bar can move between two window completions. [doneFraction] is the decoded part of the audio
 * (0–1) at the last completion, at [completedAtMs] (monotonic milliseconds). [windowEndFraction] is the end of the
 * window being decoded now. [msPerFraction] is the measured time to decode the whole audio. The value changes only
 * when a window completes or a new window begins, so it does not make a new progress state at each tick.
 */
data class ProgressAnchor(
    val doneFraction: Float,
    val windowEndFraction: Float,
    val msPerFraction: Float,
    val completedAtMs: Long,
)

/**
 * Measures how fast the windows complete, for the [ProgressAnchor] of the progress bar. Use one per run. The first
 * completion of a run includes the model load, so it only sets the start; each later completion updates a smoothed
 * time per millisecond of audio. Not thread-safe.
 */
class ProgressPace {
    private var doneMs = -1L
    private var completedAtMs = 0L
    private var msPerAudioMs: Double? = null

    /** A window completed: [doneMs] of the audio is decoded at [atMs] (monotonic milliseconds). */
    fun complete(doneMs: Long, atMs: Long) {
        val previous = this.doneMs
        if (doneMs <= previous) return
        if (previous >= 0 && atMs > completedAtMs) {
            val measured = (atMs - completedAtMs).toDouble() / (doneMs - previous)
            msPerAudioMs = msPerAudioMs?.let { it * (1 - WEIGHT) + measured * WEIGHT } ?: measured
        }
        this.doneMs = doneMs
        completedAtMs = atMs
    }

    /**
     * The anchor for the window that ends at [windowEndMs] of [totalMs], with the fractions capped at [cap]. Null
     * until two windows completed in this run, while the total is unknown, or when the window does not go past the
     * decoded audio (between a completion and the start of the next window).
     */
    fun anchor(totalMs: Long, windowEndMs: Long, cap: Float): ProgressAnchor? {
        val pace = msPerAudioMs
        if (pace == null || totalMs <= 0 || windowEndMs <= doneMs) return null
        val done = (doneMs.toFloat() / totalMs).coerceIn(0f, cap)
        val end = (windowEndMs.toFloat() / totalMs).coerceIn(done, cap)
        return ProgressAnchor(done, end, (pace * totalMs).toFloat(), completedAtMs)
    }

    private companion object {
        /** The weight of the newest window in the smoothed pace, as in [TranscriptionProgress]. */
        const val WEIGHT = 0.2
    }
}
