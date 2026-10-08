package org.cassini.android

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DeviceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun fixture(name: String = "italian-smoke.wav"): File = File(context.cacheDir, name).also { file ->
        instrumentation.context.assets.open(name).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
    }

    @Test fun importsCompressedAudioOnTheSameClock() {
        val reference = AudioDecoder.decode(context, Uri.fromFile(fixture()))
        for (name in listOf("italian-smoke.m4a", "italian-smoke.mp3", "italian-smoke.opus")) {
            val file = fixture(name)
            try {
                val audio = AudioDecoder.decode(context, Uri.fromFile(file))
                assertTrue("$name duration ${audio.durationMs}", kotlin.math.abs(audio.durationMs - reference.durationMs) <= 100)
                fun onset(pcm: PcmAudio) = pcm.samples.indexOfFirst { kotlin.math.abs(it) > .1f } * 1000L / pcm.sampleRate
                assertTrue("$name speech onset shifted", kotlin.math.abs(onset(audio) - onset(reference)) <= 100)
            } finally { file.delete() }
        }
    }

    /** The microphone format: 48 kHz AAC, whose 1024-sample frames last a fractional 21333.3 µs. */
    private fun encodeAacTone(file: File, seconds: Int = 3) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 96000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo(); val total = 48000 * seconds
            var cursor = 0; var queuedEos = false; var eos = false; var track = -1
            while (!eos) {
                val input = if (queuedEos) -1 else codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = codec.getInputBuffer(input)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN) }
                    val n = minOf(buffer.remaining() / 2, total - cursor)
                    // Offset from zero so that silence can only come from misplaced decoder output.
                    repeat(n) { buffer.putShort(((.4 + .3 * sin(2 * PI * 440 * (cursor + it) / 48000)) * 32767).toInt().toShort()) }
                    codec.queueInputBuffer(input, 0, n * 2, cursor * 1_000_000L / 48000, if (n == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    cursor += n; queuedEos = n == 0
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                else if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) muxer.writeSampleData(track, codec.getOutputBuffer(output)!!, info)
                    eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                }
            }
            codec.stop(); muxer.stop()
        } finally { codec.release(); muxer.release() }
    }

    @Test fun compressedFramesStayContiguousAtTheRecordingRate() {
        val file = File(context.cacheDir, "tone-48k.m4a")
        try {
            encodeAacTone(file)
            val samples = AudioDecoder.decode(context, Uri.fromFile(file)).samples
            assertTrue("Decoded ${samples.size} samples", samples.size > 48000 * 2)
            // Codec timestamps are whole microseconds. Placing frames by a truncated time leaves single silent samples.
            val holes = (4096 until samples.size - 4096).filter { samples[it] == 0f && abs(samples[it - 1]) > .05f && abs(samples[it + 1]) > .05f }
            assertTrue("${holes.size} silent samples inside a continuous tone, first at ${holes.take(4)}", holes.isEmpty())
        } finally { file.delete() }
    }

    @Test fun importsRealItalianAudioAndPreservesItsDuration() {
        val file = fixture()
        try {
            val audio = AudioDecoder.decode(context, Uri.fromFile(file))
            assertEquals(16000, audio.sampleRate)
            assertTrue(audio.durationMs in 15000..16000)
            assertTrue(audio.samples.any { kotlin.math.abs(it) > .01f })
        } finally { file.delete() }
    }

    @Test fun speechDetectorFindsOrderedSpansInsideTheRecording() {
        if (InstrumentationRegistry.getArguments().getString("downloadModels") == "true") ModelStore.installVad(context.filesDir) { }
        assumeTrue("Install the speech detector first", ModelStore.vadReady(context.filesDir))
        val file = fixture()
        try {
            val speech = AudioDecoder.decode(context, Uri.fromFile(file))
            // The same recording at the microphone's rate, so spans must come back on that clock.
            val upsampled = PcmAudio(FloatArray(speech.samples.size * 3) { speech.samples[it / 3] }, speech.sampleRate * 3)
            SpeechDetector(ModelStore.vadPath(context.filesDir)).use { detector ->
                for (audio in listOf(speech, upsampled)) {
                    val spans = ArrayList<Span>()
                    val paddedTailMs = detector.detect(audio) { spans += it }
                    assertTrue("${audio.sampleRate} Hz: $spans", spans.isNotEmpty())
                    assertTrue(spans.all { it.start >= 0 && it.end <= audio.samples.size && it.start < it.end })
                    assertTrue(spans.zipWithNext().all { (a, b) -> a.end <= b.start })
                    assertTrue("Most of this recording is speech", spans.sumOf { it.length.toLong() } * 2 > audio.samples.size)
                    assertTrue(paddedTailMs in 0..32)
                }
                val silence = ArrayList<Span>()
                detector.detect(PcmAudio(FloatArray(48000), 48000)) { silence += it }
                assertTrue("Silence has no speech: $silence", silence.isEmpty())
            }
        } finally { file.delete() }
    }

    @Test fun nativeParakeetProducesTimedItalianWords() {
        val arguments = InstrumentationRegistry.getArguments()
        val models = ModelStore(File(context.filesDir, "parakeet-v3"))
        if (arguments.getString("downloadModels") == "true") {
            models.install { message -> instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") }) }
            ModelStore.installVad(context.filesDir) { }
        }
        assumeTrue("Download Parakeet in the app before running native inference test", models.ready())
        val detector = ModelStore.vadPath(context.filesDir).takeIf { ModelStore.vadReady(context.filesDir) }
        val sample = arguments.getString("audioSample")
        val youtube = sample == "youtube" || sample == "youtube-long"
        val file = fixture(when (sample) {
            "youtube-long" -> "youtube-long.wav"
            "youtube" -> "youtube-smoke.wav"
            else -> "italian-smoke.wav"
        })
        try {
            val audio = AudioDecoder.decode(context, Uri.fromFile(file))
            if (sample == "youtube-long") assertEquals(180000L, audio.durationMs)
            val began = System.nanoTime()
            val steps = ArrayList<Parakeet.Progress>()
            val transcript = Parakeet.transcribe(audio, models, detector) { steps += it }
            val elapsedMs = (System.nanoTime() - began) / 1_000_000
            // Pieces are decoded one after another: the position only moves forward and ends at the recording's end.
            assertTrue(steps.zipWithNext().all { (a, b) -> b.doneMs >= a.doneMs && b.elapsedMs >= a.elapsedMs })
            assertEquals(audio.durationMs, steps.last().doneMs)
            assertTrue("Every word must be settled before packaging", steps.last().pending.isEmpty() && steps.last().settled >= transcript.words.size)
            assertTrue("Words should exist while part of the recording is still to be decoded",
                steps.any { it.hasWords && it.doneMs in 1 until audio.durationMs } || audio.durationMs < 20_000)
            val peakRssKiB = File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("VmHWM:") }?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
            val text = transcript.words.joinToString(" ") { it.text }
            File(context.filesDir, "device-smoke.${models.precision}.words.json").writeText(transcript.json())
            val metrics = JSONObject()
                .put("audioDurationMs", audio.durationMs).put("inferenceMs", elapsedMs)
                .put("precision", models.precision).put("peakRssKiB", peakRssKiB)
                .put("audioSample", when (sample) {
                    "youtube-long" -> "YouTube UmZwQf5TV3c, 28–208 seconds"
                    "youtube" -> "YouTube UmZwQf5TV3c, 28–58 seconds"
                    else -> "FLEURS Italian dev"
                })
                .put("text", text).toString(2)
            File(context.filesDir, "device-smoke.${models.precision}.metrics.json").writeText(metrics)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$metrics\n") })
            assertTrue("Recognized: $text", transcript.words.size >= 10)
            if (!youtube) assertTrue("Recognized: $text", text.contains("settentrionale", ignoreCase = true))
            assertTrue(transcript.words.all { it.startMs >= 0 && it.endMs >= it.startMs })
            assertTrue(transcript.words.all { it.startMs <= audio.durationMs + 500 })
            if (sample == "youtube-long") assertTrue("Long sample should reach its final 30 seconds",
                transcript.words.any { it.startMs >= 150000 })
        } finally { file.delete() }
    }
}
