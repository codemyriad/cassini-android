package io.codemyriad.cassini.core.asr.whistle

/** Raw 16 kHz track indices, with an exclusive end; no padding or timestamp placement. */
data class WhistleSpeechSegment(val startSample: Long, val endSample: Long, val reason: String)

/**
 * Desktop SpeechGate decisions, separate from Parakeet's sherpa VAD wrapper. Feeds exactly 512 raw samples to
 * [probability]; the caller applies the track's high-pass and stateful Silero model there, without changing raw
 * recognizer PCM. Both callbacks run synchronously. Their frame is borrowed and must not be retained or mutated.
 *
 * Retains one frame and three votes, not a whole track. Pre-roll is a sample range in the caller's raw scratch.
 * [finish] does not evaluate or zero-pad a partial VAD frame, but includes its real samples in an active segment.
 * Any callback failure aborts this gate; a retry needs a new track instance and the matching model/filter reset.
 */
class WhistleSpeechGate(
    private val probability: (FloatArray) -> Float,
    private val onSegment: (WhistleSpeechSegment) -> Unit,
) {
    private val frame = FloatArray(FRAME)
    private val votes = BooleanArray(VOTE_WINDOW)
    private var used = 0
    private var position = 0L
    private var pre = 0
    private var voteAt = 0
    private var active = false
    private var quiet = 0
    private var duration = 0L
    private var start = 0L
    private var closed = false
    private var failed = false

    /** Primitive track state only. Callbacks/native pointers are never persisted. Arrays are copied on both sides. */
    @Suppress("LongParameterList") // One member per bounded gate state field.
    data class State(
        val frame: FloatArray,
        val votes: BooleanArray,
        val used: Int,
        val position: Long,
        val pre: Int,
        val voteAt: Int,
        val active: Boolean,
        val quiet: Int,
        val duration: Long,
        val start: Long,
    )

    fun snapshot(): State {
        check(!closed && !failed)
        return State(frame.copyOf(), votes.copyOf(), used, position, pre, voteAt, active, quiet, duration, start)
    }

    /** Restore only into a fresh gate; validation completes before the live state changes. */
    fun restore(state: State) {
        check(position == 0L && used == 0 && !closed && !failed)
        require(state.frame.size == FRAME && state.frame.all { it.isFinite() })
        require(state.votes.size == VOTE_WINDOW && state.voteAt in 0 until VOTE_WINDOW)
        require(state.used in 0 until FRAME && state.position >= 0 && state.position % FRAME == 0L)
        require(state.pre in 0..PRE_FRAMES && state.pre.toLong() * FRAME <= state.position && state.quiet in 0..MAX_QUIET)
        require(state.duration in 0..(LIMIT + FRAME) && state.start in 0..state.position)
        require(!state.active || (state.pre == 0 && state.duration > 0 && state.start + state.duration == state.position))
        state.frame.copyInto(frame)
        state.votes.copyInto(votes)
        used = state.used
        position = state.position
        pre = state.pre
        voteAt = state.voteAt
        active = state.active
        quiet = state.quiet
        duration = state.duration
        start = state.start
    }

    fun accept(samples: FloatArray, size: Int = samples.size) {
        check(!closed && !failed) { "Whistle speech gate is closed" }
        require(size in 0..samples.size)
        failed = true
        var at = 0
        while (at < size) {
            val count = minOf(FRAME - used, size - at)
            samples.copyInto(frame, used, at, at + count)
            used += count
            at += count
            if (used == FRAME) {
                consumeFrame()
                used = 0
            }
        }
        failed = false
    }

    fun finish() {
        check(!closed && !failed) { "Whistle speech gate is closed" }
        closed = true
        if (active) onSegment(WhistleSpeechSegment(start, position + used, "flush"))
    }

    private fun consumeFrame() {
        val value = probability(frame)
        require(value.isFinite() && value in 0f..1f) { "Invalid Silero probability" }
        position += FRAME
        val voiced = value >= if (active) RELEASE else ATTACK
        if (!active) {
            pre = minOf(PRE_FRAMES, pre + 1)
            votes[voteAt] = voiced
            voteAt = (voteAt + 1) % VOTE_WINDOW
            if (votes.count { it } < REQUIRED_VOTES) return
            active = true
            duration = pre.toLong() * FRAME
            start = position - duration
            pre = 0
            votes.fill(false)
            voteAt = 0
        } else {
            duration += FRAME
        }
        quiet = if (voiced) 0 else quiet + 1
        val reason = when {
            quiet * FRAME >= SILENCE -> "silence"
            duration >= PAUSE_AFTER && quiet * FRAME >= PAUSE -> "durationPause"
            duration >= LIMIT -> "durationLimit"
            else -> null
        }
        if (reason != null) {
            onSegment(WhistleSpeechSegment(start, position, reason))
            active = false
            quiet = 0
        }
    }

    companion object {
        private const val FRAME = 512
        private const val VOTE_WINDOW = 3
        private const val REQUIRED_VOTES = 2
        private const val PRE_FRAMES = 10
        private const val ATTACK = 0.5f
        private const val RELEASE = 0.35f
        private const val SILENCE = 9600
        private const val MAX_QUIET = 19
        private const val PAUSE_AFTER = 224000
        private const val PAUSE = 2048
        private const val LIMIT = 240000
    }
}
