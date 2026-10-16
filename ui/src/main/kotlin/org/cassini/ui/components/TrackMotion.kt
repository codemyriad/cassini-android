package org.cassini.ui.components

import androidx.compose.runtime.Immutable
import kotlin.math.exp

/**
 * Where the progress card's track can move between two window completions. [doneFraction] (0–1) was decoded at
 * [completedAtMs] (`SystemClock.elapsedRealtime`). The window being decoded ends at [windowEndFraction], and
 * [msPerFraction] is the measured time for the whole audio. It changes only when a window completes or begins.
 */
@Immutable
data class TrackAnchor(
    val doneFraction: Float,
    val windowEndFraction: Float,
    val msPerFraction: Float,
    val completedAtMs: Long,
) {
    /**
     * The predicted fraction at [nowMs]: it starts at [doneFraction], moves at the measured pace, and stops at
     * [windowEndFraction]. It never goes back as time goes on.
     */
    fun predict(nowMs: Long): Float {
        val end = maxOf(doneFraction, windowEndFraction)
        if (msPerFraction <= 0f) return doneFraction
        val ahead = (nowMs - completedAtMs).coerceAtLeast(0L) / msPerFraction
        return (doneFraction + ahead).coerceIn(doneFraction, end)
    }

    /** The time at which [predict] gives [fraction], or [completedAtMs] when it is at or below the done part. */
    fun reachesAtMs(fraction: Float): Long {
        val ahead = fraction - doneFraction
        if (ahead <= 0f || msPerFraction <= 0f) return completedAtMs
        return completedAtMs + (ahead * msPerFraction).toLong() + 1
    }
}

/** The pure steps of the moving track: the aim of each frame and the glide to it. */
object TrackMotion {
    /** The time constant of the glide to a new value. */
    const val GLIDE_MS = 250f

    /** A value below the shown one by more than this is a new run, not the noise of rounding: the track goes back. */
    const val RESET = 0.01f

    /** Closer than this, the glide is complete. */
    private const val SETTLED = 1e-4f

    /** What the track moves to at [nowMs]: the published [fraction], or the prediction of [anchor] when it is ahead. */
    fun aim(fraction: Float, anchor: TrackAnchor?, nowMs: Long): Float =
        if (anchor == null) fraction else maxOf(fraction, anchor.predict(nowMs))

    /**
     * One frame of the glide from [shown] to [aim] after [elapsedMs]. The track never goes back, except to a value
     * more than [RESET] below (a new run), where it goes at once.
     */
    fun step(shown: Float, aim: Float, elapsedMs: Long): Float = when {
        aim < shown - RESET -> aim
        aim <= shown -> shown
        aim - shown < SETTLED -> aim
        else -> shown + (aim - shown) * (1f - exp(-elapsedMs.coerceAtLeast(0L) / GLIDE_MS))
    }
}
