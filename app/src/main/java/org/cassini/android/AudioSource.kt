package org.cassini.android

/**
 * Recording samples that may still be arriving. Transcription reads windows from it instead of
 * holding the whole recording, so it can start while the decoder is still writing the cache.
 */
internal interface AudioSource {
    val sampleRate: Int
    /** Samples the recording is expected to have; exact once [ended]. */
    val expectedSize: Int
    /**
     * Blocks until [end] samples exist or the input has ended and returns how many are readable,
     * fewer than [end] only at the end. Throws [UserFacingException] when the input failed.
     */
    fun await(end: Int): Int
    /** Whether every sample has arrived; [await] then never blocks. */
    val ended: Boolean
    /** Copies [length] samples from [start] into [into] at [offset]; they must have been awaited. */
    fun read(start: Int, length: Int, into: FloatArray, offset: Int = 0)

    /** The total length, waiting for the end of the input. */
    fun size(): Int = await(Int.MAX_VALUE)

    fun read(start: Int, length: Int): FloatArray = FloatArray(length).also { read(start, length, it) }
}

/** A recording already decoded in full. */
internal class ArraySource(val audio: PcmAudio) : AudioSource {
    override val sampleRate get() = audio.sampleRate
    override val expectedSize get() = audio.samples.size
    override val ended get() = true
    override fun await(end: Int) = minOf(end, audio.samples.size)
    override fun read(start: Int, length: Int, into: FloatArray, offset: Int) {
        audio.samples.copyInto(into, offset, start, start + length)
    }
}

/**
 * The 16 kHz [PcmCache] a decoder is writing. A decode that stopped early is reported as
 * [failure] by the first read past what it wrote, never as the end of the recording.
 */
internal class CacheSource(private val cache: PcmCache, private val failure: () -> Throwable? = { null }) : AudioSource {
    override val sampleRate get() = Limits.ASR_RATE
    override val expectedSize get() = (if (cache.state.complete) cache.available else cache.state.expectedSamples.coerceAtLeast(cache.available))
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    override val ended get() = cache.state.complete
    override fun await(end: Int): Int {
        val got = try { cache.awaitAvailable(end.toLong()) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UserFacingException(Failure.CANCELLED)
        }
        if (got < end && !cache.state.complete) throw failure() ?: UserFacingException(Failure.STALLED)
        return got.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    override fun read(start: Int, length: Int, into: FloatArray, offset: Int) = cache.read(start.toLong(), length, into, offset)
}

/** Sample access for [WordGate], which reads a little around each word in recording order. */
internal interface SampleReader {
    val size: Int
    operator fun get(index: Int): Float
}

internal class ArrayReader(private val samples: FloatArray) : SampleReader {
    override val size get() = samples.size
    override fun get(index: Int) = samples[index]
}

/** Reads [source] through one block, so the word-by-word walk of the energy gate stays sequential I/O. */
internal class BlockReader(private val source: AudioSource, override val size: Int, private val blockSize: Int = 1 shl 16) : SampleReader {
    private val block = FloatArray(blockSize)
    private var blockStart = -1
    private var blockLength = 0
    override fun get(index: Int): Float {
        if (blockStart < 0 || index < blockStart || index >= blockStart + blockLength) {
            blockStart = index / blockSize * blockSize
            blockLength = minOf(blockSize, size - blockStart)
            source.read(blockStart, blockLength, block)
        }
        return block[index - blockStart]
    }
}
