package org.cassini.android

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Silero VAD over a 16 kHz copy of the recording, configured and fed as the desktop pipeline does on a
 * stock sherpa runtime (gocassini transcribe/stt.go). Speech spans reach the caller in order, at the
 * recording's own rate, as soon as the detector closes them, so each can be decoded while later audio
 * is still being scanned. One instance owns one native detector: [close] it in a finally block.
 */
internal class SpeechDetector(modelPath: String) : AutoCloseable {
    /** The calls the feed loop makes, so the loop can be checked on the JVM without the native library. */
    internal interface Stream {
        fun reset()
        fun acceptWaveform(window: FloatArray)
        fun empty(): Boolean
        fun front(): SpeechSegment
        fun pop()
        fun flush()
    }

    companion object {
        /** Below Silero's stock 0.5 so 420-500 ms acknowledgements are not missed. */
        const val THRESHOLD = 0.18f
        const val MIN_SILENCE_SECONDS = 0.5f
        const val MIN_SPEECH_SECONDS = 0.10f
        const val MAX_SPEECH_SECONDS = 25f
        /** Samples per detector call at 16 kHz. Sherpa's flush never evaluates a buffered partial window. */
        const val WINDOW = 512
        /** Drain completed segments every 5 s of fed audio so the detector's buffer stays small. */
        const val DRAIN_EVERY = VAD_SAMPLE_RATE * 5

        /** Silero is a tiny stateful model run per 32 ms window: fastest on one CPU thread. */
        fun config(modelPath: String) = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath, threshold = THRESHOLD, minSilenceDuration = MIN_SILENCE_SECONDS,
                minSpeechDuration = MIN_SPEECH_SECONDS, windowSize = WINDOW, maxSpeechDuration = MAX_SPEECH_SECONDS,
            ),
            sampleRate = VAD_SAMPLE_RATE, numThreads = 1, provider = "cpu", debug = false,
        )

        /**
         * Resets [stream] once, feeds exact [WINDOW]-sample windows of 16 kHz [samples], zero-pads the last
         * partial window, drains completed segments every [DRAIN_EVERY] real samples fed, then flushes and
         * drains the rest. Empty segments are skipped. Interruption is checked at every drain. Returns the
         * zeros appended to the last window, 0 when it was full: the detector heard that much synthetic tail.
         */
        fun feed(stream: Stream, samples: FloatArray, onSegment: (SpeechSegment) -> Unit): Int {
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
                recordingSpan(segment.start, segment.samples.size, audio.sampleRate, audio.samples.size)?.let(onSpan)
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

    private val vad: Vad

    init {
        requireUser(File(modelPath).isFile, Failure.MODEL)
        vad = Vad(config = config(modelPath))
    }

    private val stream = object : Stream {
        override fun reset() = vad.reset()
        override fun acceptWaveform(window: FloatArray) = vad.acceptWaveform(window)
        override fun empty() = vad.empty()
        override fun front() = vad.front()
        override fun pop() = vad.pop()
        override fun flush() = vad.flush()
    }

    /**
     * Calls [onSpan] with each speech span of [audio], in order, and returns the detector's tail padding in
     * milliseconds for the timeline clamp (see [scan]). Reusable: every call starts from a reset.
     */
    fun detect(audio: PcmAudio, onSpan: (Span) -> Unit): Long = scan(stream, audio, onSpan)

    override fun close() = vad.release()
}
