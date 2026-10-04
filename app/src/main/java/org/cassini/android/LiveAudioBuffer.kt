package org.cassini.android

/**
 * Bounded, append-only 16-bit audio shared by one capture producer and one decoding consumer.
 * Every sample is retained up to [capacity], so a consumer that falls behind can still catch up and the final
 * gate sees the whole recording. Paused audio is never appended, so sample positions are the recording clock.
 * The producer never waits for the consumer.
 */
internal class LiveAudioBuffer(
    maxSeconds: Int = DEFAULT_MAX_SECONDS,
    val sampleRate: Int = 16000,
    chunkMs: Int = 5000,
    overlapMs: Int = 1000,
) {
    private val lock = Object()
    private val samples = ShortArray(maxSeconds * sampleRate)
    private val chunk = chunkMs * sampleRate / 1000
    private val overlap = overlapMs * sampleRate / 1000
    private var count = 0
    private var done = false
    private var dropped = false

    init {
        require(maxSeconds > 0 && sampleRate > 0 && chunk > 0 && overlap in 0 until chunk) { "Invalid buffer geometry" }
    }

    val capacity: Int get() = samples.size
    val size: Int get() = synchronized(lock) { count }
    val finished: Boolean get() = synchronized(lock) { done }
    val cancelled: Boolean get() = synchronized(lock) { dropped }

    /** Copies the first [count] samples; returns how many fit. Returns 0 once finished or cancelled. */
    fun append(source: ShortArray, count: Int = source.size): Int {
        require(count in 0..source.size) { "count $count outside 0..${source.size}" }
        synchronized(lock) {
            if (done || dropped) return 0
            val accepted = minOf(count, samples.size - this.count)
            if (accepted > 0) {
                System.arraycopy(source, 0, samples, this.count, accepted)
                this.count += accepted
                lock.notifyAll()
            }
            return accepted
        }
    }

    /** Ends the recording: the consumer drains what is left, then sees null. */
    fun finish() = synchronized(lock) { done = true; lock.notifyAll() }

    /** Abandons the recording: waiting and future [awaitChunk] calls return null at once. */
    fun cancel() = synchronized(lock) { dropped = true; lock.notifyAll() }

    /** Appended samples never change, so the copy runs outside the lock. */
    fun snapshot(start: Int = 0, end: Int = size): PcmAudio {
        val limit = size
        require(start in 0..end && end <= limit) { "Range $start..$end outside 0..$limit" }
        val out = FloatArray(end - start)
        for (i in out.indices) out[i] = samples[start + i] / 32768f
        return PcmAudio(out, sampleRate)
    }

    /**
     * Blocks for the next span to decode after one that ended at [previousEnd] (0 for the first call). Full chunks
     * are [chunkMs] long, the first from 0 and each later one starting [overlapMs] before [previousEnd]. After
     * [finish] the remainder, however short, is returned the same way. Null when drained or cancelled.
     */
    @Throws(InterruptedException::class)
    fun awaitChunk(previousEnd: Int): Span? {
        synchronized(lock) {
            require(previousEnd >= 0 && previousEnd <= count) { "previousEnd $previousEnd outside 0..$count" }
            val start = maxOf(0, previousEnd - overlap)
            val target = previousEnd + chunk
            while (true) {
                if (dropped) return null
                if (count >= target) return Span(start, target)
                if (done) return if (count > previousEnd) Span(start, count) else null
                lock.wait()
            }
        }
    }

    companion object { const val DEFAULT_MAX_SECONDS = 179 }
}
