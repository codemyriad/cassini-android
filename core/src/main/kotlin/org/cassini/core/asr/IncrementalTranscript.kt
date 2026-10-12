package org.cassini.core.asr

import org.cassini.core.Failure
import org.cassini.core.audio.Limits
import org.cassini.core.audio.PcmAudio
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.requireUser

/**
 * What an [IncrementalTranscript] restores from: checkpoint.json `recognition.chunks` (CheckpointState holds the
 * JSON codec). [window] holds the samples from [start]; [end] is the last decoded sample; [words] are the words so far.
 */
class IncrementalState(val start: Long, val end: Long, val window: FloatArray, val words: List<Word>)

/**
 * Consumes the resampler's borrowed buffers synchronously. Recognition starts after 28 s of audio,
 * before the container has been fully decoded. Two seconds of recorded overlap protect words at
 * boundaries; the existing seam reconciliation keeps each word once on the recording clock.
 * Only a window is copied here; encoding consumes the same decoded blocks, and voiceprints
 * retain a bounded selection of clean clips on disk.
 */
class IncrementalTranscript(restored: IncrementalState? = null, private val recognize: (PcmAudio, Long) -> Transcript) {
    private val rate = Limits.ASR_RATE
    private val window = FloatArray(28 * rate)
    private val overlap = 2 * rate
    private var size = 0
    private var start = 0L
    private var decodedEnd = 0L
    private var finished = false
    private var words = emptyList<Word>()
    val positionSamples get() = start + size
    val doneMs get() = decodedEnd * 1000 / rate
    init {
        restored?.let {
            start = it.start; decodedEnd = it.end
            val samples = it.window
            require(samples.size <= window.size)
            require(start >= 0 && decodedEnd in 0..Limits.MAX_SAMPLES && start + samples.size <= Limits.MAX_SAMPLES && decodedEnd <= start + samples.size && decodedEnd >= start)
            samples.copyInto(window); size = samples.size
            words = it.words
        }
    }
    fun checkpoint() = IncrementalState(start, decodedEnd, window.copyOf(size), words)

    fun accept(samples: FloatArray, count: Int, onProgress: (Long, List<Word>) -> Unit = { _, _ -> }) {
        check(!finished)
        require(count in 0..samples.size)
        var offset = 0
        while (offset < count) {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val n = minOf(window.size - size, count - offset)
            samples.copyInto(window, size, offset, offset + n)
            size += n
            offset += n
            if (size == window.size) {
                decode()
                onProgress(decodedEnd * 1000 / rate, words)
                window.copyInto(window, 0, size - overlap, size)
                start += size - overlap
                size = overlap
            }
        }
    }

    private fun decode() {
        val offsetMs = start * 1000 / rate
        val recognized = recognize(PcmAudio(window.copyOf(size), rate), offsetMs)
        val found = recognized.words.map {
            it.copy(startMs = it.startMs + offsetMs, endMs = it.endMs + offsetMs)
        }
        words = if (start == 0L) found else SeamMerge.splice(words, found, offsetMs, overlap * 1000L / rate)
        decodedEnd = start + size
    }

    fun finish(): Transcript {
        check(!finished)
        finished = true
        // An exact window end leaves just its already-recognized overlap; do not decode it twice.
        if (start + size > decodedEnd) decode()
        return Transcript(words)
    }

    companion object {
        /** The longest window a checkpoint can hold: 28 s at the recognition rate. */
        const val WINDOW_SAMPLES = 28 * Limits.ASR_RATE
    }
}
