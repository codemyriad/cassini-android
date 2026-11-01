package org.cassini.android.playback

import kotlin.math.abs

/** The playback speeds of the Note screen's speed button (coordination D53; iOS has the same 0.75–2× range). */
object PlaybackSpeeds {
    @Suppress("MagicNumber") // The speeds themselves.
    val STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** The step after [current], wrapping from 2× back to 0.75×. */
    fun next(current: Float): Float = STEPS[(STEPS.indexOf(nearest(current)) + 1) % STEPS.size]

    /** The step closest to [speed]; a stored value that is no step (or not a number) gives the nearest one, or 1×. */
    fun nearest(speed: Float): Float = if (speed.isNaN()) 1f else STEPS.minBy { abs(it - speed) }
}
