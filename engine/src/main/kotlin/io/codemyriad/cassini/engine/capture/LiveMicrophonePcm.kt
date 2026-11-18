package io.codemyriad.cassini.engine.capture

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** Optional single-consumer tap for one Opus capture. Positions use the stored audio clock. */
class LiveMicrophonePcm(capacity: Int = DEFAULT_BLOCKS) {
    data class Block(val firstFrame: Long, val pcm16: ShortArray)

    private val channel = Channel<Block>(capacity.also { require(it in 1..MAX_BLOCKS) })
    val blocks: ReceiveChannel<Block> get() = channel

    @Volatile var endFrame: Long = 0
        private set

    internal fun offer(at: Long, pcm: ShortArray, count: Int) {
        endFrame = at + count
        // The recorder reuses its input buffer. Never hand ownership of that buffer to the consumer.
        channel.trySend(Block(at, pcm.copyOf(count)))
    }

    internal fun finish() {
        channel.close()
    }

    /** Discards queued preview input. Closing the preview does not stop recording. */
    fun cancel() {
        channel.cancel()
    }
    private companion object {
        const val DEFAULT_BLOCKS = 32
        const val MAX_BLOCKS = 128
    }
}
