package org.cassini.core.asr

import org.cassini.core.Failure
import org.cassini.core.audio.PcmAudio
import org.cassini.core.audio.VAD_SAMPLE_RATE
import org.cassini.core.audio.resampleTo16k
import org.cassini.core.requireUser

/**
 * The feed loop of the Silero speech detector over a 16 kHz copy of the recording, configured and fed as the desktop
 * pipeline does on a stock sherpa runtime (gocassini transcribe/stt.go). Speech spans reach the caller in order, at
 * the recording's own rate, as soon as the detector closes them. The native detector sits behind [Stream].
 */
object SpeechFeed {
    /** A detected segment: [samples] at 16 kHz from index [startSample]. */
    class Segment(val startSample: Int, val samples: FloatArray)

    /** The calls the feed loop makes, so the loop can be checked on the JVM without the native library. */
    interface Stream {
        fun reset()
        fun acceptWaveform(window: FloatArray)
        fun empty(): Boolean
        fun front(): Segment
        fun pop()
        fun flush()
    }

    /** Below Silero's stock 0.5 so 420-500 ms acknowledgements are not missed. */
    const val THRESHOLD = 0.18f
    const val MIN_SILENCE_SECONDS = 0.5f
    const val MIN_SPEECH_SECONDS = 0.10f
    const val MAX_SPEECH_SECONDS = 25f
    /** Samples per detector call at 16 kHz. Sherpa's flush never evaluates a buffered partial window. */
    const val WINDOW = 512
    /** Drain completed segments every 5 s of fed audio so the detector's buffer stays small. */
    const val DRAIN_EVERY = VAD_SAMPLE_RATE * 5

    /**
     * Resets [stream] once, feeds exact [WINDOW]-sample windows of 16 kHz [samples], zero-pads the last
     * partial window, drains completed segments every [DRAIN_EVERY] real samples fed, then flushes and
     * drains the rest. Empty segments are skipped. Interruption is checked at every drain. Returns the
     * zeros appended to the last window, 0 when it was full: the detector heard that much synthetic tail.
     */
    fun feed(stream: Stream, samples: FloatArray, onSegment: (Segment) -> Unit): Int {
        fun drain() {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            while (!stream.empty()) {
                val segment = stream.front()
                stream.pop()
                if (segment.samples.isNotEmpty()) onSegment(segment)
            }
        }
        stream.reset()
        var sinceDrain = 0
        var padding = 0
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + WINDOW, samples.size)
            // A short final window keeps the zeros it was allocated with.
            padding = WINDOW - (end - offset)
            stream.acceptWaveform(FloatArray(WINDOW).also { samples.copyInto(it, 0, offset, end) })
            sinceDrain += end - offset
            if (sinceDrain >= DRAIN_EVERY) {
                sinceDrain = 0
                drain()
            }
            offset += WINDOW
        }
        stream.flush()
        drain()
        return padding
    }

    /**
     * Feeds the 16 kHz copy of [audio] to [stream], passes each span mapped to the recording to [onSpan],
     * and returns the zero padding of the last window in milliseconds, rounded up. Spans stop at the
     * recording's end, so a caller clamping decoded words to the timeline needs this value to keep a
     * word stamped inside that padding as a zero-length word at the end, as the desktop does.
     */
    fun scan(stream: Stream, audio: PcmAudio, onSpan: (Span) -> Unit): Long {
        val padding = feed(stream, resampleTo16k(audio.samples, audio.sampleRate)) { segment ->
            recordingSpan(segment.startSample, segment.samples.size, audio.sampleRate, audio.samples.size)?.let(onSpan)
        }
        return SpeechWindows.ceilMs(padding, VAD_SAMPLE_RATE)
    }

    /**
     * Maps a segment of [length] samples at 16 kHz index [start] to the recording: start rounded down, end
     * rounded up so no speech is cut, end clamped to [recordingLength] to drop the detector's zero padding.
     * Null when nothing of it lies inside the recording.
     */
    fun recordingSpan(start: Int, length: Int, rate: Int, recordingLength: Int): Span? {
        val first = start.toLong() * rate / VAD_SAMPLE_RATE
        val last = minOf(recordingLength.toLong(), ((start.toLong() + length) * rate + VAD_SAMPLE_RATE - 1) / VAD_SAMPLE_RATE)
        return if (first < last) Span(first.toInt(), last.toInt()) else null
    }
}
