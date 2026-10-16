package org.cassini.ui.note

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlinx.collections.immutable.ImmutableList
import org.cassini.ui.components.TurnUi
import org.cassini.ui.components.WordRef
import kotlin.math.abs

/**
 * A published player position: [positionMs] as read at [sampledAtNanos] (`System.nanoTime`), with the play state and
 * [speed]. [epoch] changes on every jump of the position (seek, sample, reload, open), so a new anchor of another
 * epoch is not slewed to.
 */
@Immutable
data class PlaybackAnchor(
    val positionMs: Long,
    val sampledAtNanos: Long,
    val playing: Boolean,
    val speed: Float = 1f,
    val epoch: Long = 0,
)

/**
 * The player position between two anchors, for each frame: the last anchor extrapolated at its speed.
 *
 * A new anchor of the same epoch while playing is reconciled: the difference from the extrapolated position at its
 * sample time fades out over [slewNanos], and the position never goes back while playing (it holds instead). A
 * difference above [snapMs], another epoch, a pause or no anchor reset the clock to the anchor.
 */
class PlaybackClock(private val slewNanos: Long = SLEW_NANOS, private val snapMs: Long = SNAP_MS) {
    private var anchor: PlaybackAnchor? = null
    private var correctionMs = 0.0
    private var lastMs = Long.MIN_VALUE

    /** Takes [next] as the anchor; the same anchor again does nothing. */
    fun update(next: PlaybackAnchor?) {
        val previous = anchor
        if (next == previous) return
        val continues = next != null && previous != null && next.playing && previous.playing &&
            next.epoch == previous.epoch
        val predicted = if (continues) extrapolate(next.sampledAtNanos) else 0.0
        anchor = next
        val error = predicted - (next?.positionMs ?: 0)
        if (continues && abs(error) <= snapMs) {
            correctionMs = error
        } else {
            correctionMs = 0.0
            lastMs = Long.MIN_VALUE
        }
    }

    /** The position at [nowNanos]; while playing, never less than the last position returned. */
    fun positionMs(nowNanos: Long): Long {
        val current = anchor ?: return 0
        if (current.playing) lastMs = maxOf(extrapolate(current, nowNanos).toLong(), lastMs)
        return if (current.playing) lastMs else current.positionMs
    }

    private fun extrapolate(nowNanos: Long): Double = anchor?.let { extrapolate(it, nowNanos) } ?: 0.0

    private fun extrapolate(current: PlaybackAnchor, nowNanos: Long): Double {
        if (!current.playing) return current.positionMs.toDouble()
        val elapsed = (nowNanos - current.sampledAtNanos).coerceAtLeast(0)
        val fade = (1.0 - elapsed.toDouble() / slewNanos).coerceAtLeast(0.0)
        return current.positionMs + elapsed / NANOS_PER_MS * current.speed + correctionMs * fade
    }

    private companion object {
        const val SLEW_NANOS = 250_000_000L
        const val SNAP_MS = 400L
        const val NANOS_PER_MS = 1_000_000.0
    }
}

/** The word starts of [turns] in order, for the active word of each frame; a lookup allocates nothing. */
class WordStarts(private val turns: ImmutableList<TurnUi>) {
    private val starts: LongArray
    private val turnOf: IntArray
    private val inTurn: IntArray

    init {
        val size = turns.sumOf { it.words.size }
        starts = LongArray(size)
        turnOf = IntArray(size)
        inTurn = IntArray(size)
        var i = 0
        turns.forEachIndexed { t, turn ->
            turn.words.forEachIndexed { w, word ->
                starts[i] = word.startMs
                turnOf[i] = t
                inTurn[i] = w
                i++
            }
        }
    }

    /** The index of the word playing at [positionMs]: the last that started at or before it; -1 before the first. */
    fun indexAt(positionMs: Long): Int {
        var low = 0
        var high = starts.size
        // The first start after positionMs; equal starts count as started, so the last of them plays.
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= positionMs) low = mid + 1 else high = mid
        }
        return low - 1
    }

    /** The word of [index] (from [indexAt]), or null for -1. */
    fun ref(index: Int): WordRef? = if (index < 0) null else WordRef(turns[turnOf[index]].id, inTurn[index])
}

/**
 * The active word: while [anchor] plays, the word at the position extrapolated each frame ([PlaybackClock]), so the
 * highlight changes at the word's start; otherwise [published]. The value changes only when the word does, so a frame
 * recomposes nothing.
 */
@Composable
internal fun frameWord(turns: ImmutableList<TurnUi>, anchor: PlaybackAnchor?, published: WordRef?): WordRef? {
    val starts = remember(turns) { WordStarts(turns) }
    val clock = remember { PlaybackClock() }
    val latest by rememberUpdatedState(anchor)
    val word = remember { mutableStateOf<WordRef?>(null) }
    val playing = anchor?.playing == true
    if (playing) {
        LaunchedEffect(starts) {
            clock.update(null)
            var shown = Int.MIN_VALUE
            try {
                while (true) {
                    withFrameNanos {
                        clock.update(latest)
                        val index = starts.indexAt(clock.positionMs(System.nanoTime()))
                        if (index != shown) {
                            shown = index
                            word.value = starts.ref(index)
                        }
                    }
                }
            } finally {
                word.value = null
            }
        }
    }
    return if (playing) word.value ?: published else published
}
