package org.cassini.android.flows

import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.cassini.android.audioDecoder
import org.cassini.android.background.Background
import org.cassini.android.notices.OpusLibrary
import org.cassini.core.audio.PcmAudio
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.OggOpus
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.engine.capture.CaptureFactory
import org.cassini.engine.opus.StreamingOpusEncoder
import org.cassini.test.CiSmoke
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The portable `.opus` document on the device (docs/architecture.md section 9): the Opus encoders of this device
 * give a clear outcome, a document written with the app's libopus encoder reads back verified, and (API 34 only)
 * the platform Opus encoder that recordings use keeps the duration and the speech clock of the source.
 */
@CiSmoke
class PortableDocumentTest {
    private val context get() = Background.context

    @Test fun theEncoderCapabilityHasAClearOutcome() {
        val native = OpusLibrary.loads()
        val platform = CaptureFactory.platformOpusEncoderNow()
        val capture = runBlocking { CaptureFactory.extension() }
        Log.i(TAG, "API ${Build.VERSION.SDK_INT}: libcassini_opus=$native platform encoder=$platform capture=$capture")
        val expected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && platform) "opus" else "m4a"
        assertEquals("Recordings use Opus only with a platform encoder on API 29+", expected, capture)
        // The app packages libcassini_opus for every ABI it ships; without it the Library says NO_ENCODER.
        assertTrue("libcassini_opus loads", native)
        assertFalse("No NO_ENCODER notice", Flows.graph.notices.facts.value.encoderMissing)

        val source = Background.assetCopy("fleurs-it-short.wav")
        val audio = File(source.parentFile, "portable-encoder.opus")
        val document = File(source.parentFile, "portable-document.opus")
        try {
            val durationMs = StreamingOpusEncoder(audio).use { encoder ->
                audioDecoder(context).stream(Uri.fromFile(source), encoder::accept).also { encoder.finish() }
            }
            val words = listOf(Word("S1", 100, 600, "Ciao."), Word("S2", 8_000, 8_500, "Benvenuti."))
            CassiniDocument.create(
                audio,
                Transcript(words, "it"),
                "Portable",
                JSONObject().put("model", "test"),
                null,
                mapOf("S1" to "Ada"),
                document,
            )
            val read = CassiniDocument.readChecked(document)
            assertEquals("ok", read.state)
            assertEquals("Ada", read.speakerLabel("S1"))
            assertEquals(words, read.selected(null)?.transcript?.words)
            val info = OggOpus.scan(document)
            assertTrue(info.validFraming)
            assertEquals(durationMs, info.durationMs)
            assertEquals(OggOpus.scan(audio).digest(), info.digest())
        } finally {
            source.delete()
            audio.delete()
            document.delete()
        }
    }

    /** The platform encoder is a device feature that varies by API level; the CI matrix checks it on API 34 only. */
    @SdkSuppress(minSdkVersion = 34, maxSdkVersion = 34)
    @Test
    fun platformOpusKeepsTheDurationAndTheSpeechClock() {
        assertTrue("API 34 has a platform Opus encoder", CaptureFactory.platformOpusEncoderNow())
        val source = Background.assetCopy("fleurs-it-short.wav")
        val opus = File(source.parentFile, "platform-opus.opus")
        try {
            val original = audioDecoder(context).decode(Uri.fromFile(source))
            val samples = platformEncode(upsample(original), opus)
            val info = OggOpus.scan(opus)
            assertTrue(info.validFraming)
            assertEquals(samples, info.sampleCount)
            assertEquals(original.durationMs, info.durationMs)
            val decoded = audioDecoder(context).decode(Uri.fromFile(opus))
            assertEquals(original.durationMs, decoded.durationMs)
            assertTrue("Speech onset moved", abs(edge(original, false) - edge(decoded, false)) < EDGE_MS)
            assertTrue("Speech tail moved", abs(edge(original, true) - edge(decoded, true)) < EDGE_MS)
        } finally {
            source.delete()
            opus.delete()
        }
    }

    /** The 16 kHz decode at 48 kHz, by linear interpolation: the rate the recorder gives the platform encoder. */
    private fun upsample(audio: PcmAudio): ShortArray {
        val ratio = RATE / audio.sampleRate
        val input = audio.samples
        return ShortArray(input.size * ratio) { index ->
            val at = index / ratio
            val next = input.getOrElse(at + 1) { input[at] }
            val value = input[at] + (next - input[at]) * (index % ratio) / ratio
            (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    /**
     * Encodes [pcm] (48 kHz mono) with the platform `audio/opus` encoder and muxes it as `OpusCapture` does: the
     * encoder's OpusHead, 20 ms frames, a zero frame to flush the lookahead and the end at pre-skip plus the input.
     */
    private fun platformEncode(pcm: ShortArray, target: File): Long {
        target.outputStream().buffered().use { out ->
            val encoder = PlatformOpus(out)
            try {
                var at = 0
                while (at < pcm.size) {
                    val count = minOf(FRAME, pcm.size - at)
                    encoder.queue(pcm.copyOfRange(at, at + count), at.toLong(), 0)
                    at += count
                    encoder.drain(0)
                }
                encoder.queue(ShortArray(FRAME), at.toLong(), 0)
                encoder.queue(ShortArray(0), at.toLong() + FRAME, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                var ended = false
                while (!ended) ended = encoder.drain(TIMEOUT_US)
                encoder.finish(pcm.size.toLong())
            } finally {
                encoder.release()
            }
        }
        return pcm.size.toLong()
    }

    /** The platform Opus encoder (48 kHz mono, 48 kb/s) writing its packets to an Ogg stream on [out]. */
    private class PlatformOpus(private val out: java.io.OutputStream) {
        private val codec = MediaCodec.createEncoderByType("audio/opus").apply {
            val format = MediaFormat.createAudioFormat("audio/opus", RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2)
            }
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        private val info = MediaCodec.BufferInfo()
        private var muxer: OggOpus.LiveMuxer? = null

        /** Queues [frame] as the samples from [atSamples], draining output while no input buffer is free. */
        fun queue(frame: ShortArray, atSamples: Long, flags: Int) {
            var index = codec.dequeueInputBuffer(TIMEOUT_US)
            while (index < 0) {
                drain(0)
                index = codec.dequeueInputBuffer(TIMEOUT_US)
            }
            val input = checkNotNull(codec.getInputBuffer(index))
            input.clear()
            input.order(ByteOrder.LITTLE_ENDIAN)
            frame.forEach { input.putShort(it) }
            codec.queueInputBuffer(index, 0, frame.size * 2, atSamples * 1_000_000 / RATE, flags)
        }

        /** Moves every ready packet to the muxer; true once the encoder signalled the end of the stream. */
        fun drain(timeoutUs: Long): Boolean {
            var index = codec.dequeueOutputBuffer(info, timeoutUs)
            while (index >= 0) {
                val buffer = checkNotNull(codec.getOutputBuffer(index))
                buffer.position(info.offset)
                val bytes = ByteArray(info.size).also { buffer.get(it) }
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (config && muxer == null) {
                    muxer = OggOpus.LiveMuxer(out, head(bytes), CassiniDocument.tagsPacket(emptyList()))
                } else if (!config && bytes.isNotEmpty()) {
                    checkNotNull(muxer) { "No OpusHead from the platform encoder" }.add(bytes)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                index = codec.dequeueOutputBuffer(info, timeoutUs)
            }
            return false
        }

        /** Ends the stream at pre-skip plus [samples], as the recorder does. */
        fun finish(samples: Long) {
            val stream = checkNotNull(muxer)
            stream.finish(stream.preSkip + samples)
        }

        fun release() {
            codec.stop()
            codec.release()
        }

        /** The OpusHead inside the encoder's codec-specific data. */
        private fun head(bytes: ByteArray): ByteArray {
            val marker = "OpusHead".toByteArray()
            val start = (0..bytes.size - HEAD_BYTES).first { p -> marker.indices.all { bytes[p + it] == marker[it] } }
            return bytes.copyOfRange(start, start + HEAD_BYTES)
        }
    }

    /** The time of the first (or, [reverse], the last) sample above the speech level, in ms. */
    private fun edge(audio: PcmAudio, reverse: Boolean): Long {
        val indices = if (reverse) audio.samples.indices.reversed() else audio.samples.indices
        return (indices.firstOrNull { abs(audio.samples[it]) > SPEECH_LEVEL } ?: 0) * 1000L / audio.sampleRate
    }

    private companion object {
        const val TAG = "CassiniCompatibility"
        const val RATE = 48_000
        const val FRAME = RATE / 50
        const val BIT_RATE = 48_000
        const val HEAD_BYTES = 19
        const val TIMEOUT_US = 10_000L
        const val EDGE_MS = 30
        const val SPEECH_LEVEL = 0.04f
    }
}
