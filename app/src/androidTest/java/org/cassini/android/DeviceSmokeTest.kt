package org.cassini.android

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

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

    @Test fun importsRealItalianAudioAndPreservesItsDuration() {
        val file = fixture()
        try {
            val audio = AudioDecoder.decode(context, Uri.fromFile(file))
            assertEquals(16000, audio.sampleRate)
            assertTrue(audio.durationMs in 15000..16000)
            assertTrue(audio.samples.any { kotlin.math.abs(it) > .01f })
        } finally { file.delete() }
    }

    @Test fun nativeParakeetProducesTimedItalianWords() {
        val arguments = InstrumentationRegistry.getArguments()
        val fp32 = arguments.getString("precision") == "fp32"
        val models = ModelStore(File(context.filesDir, if (fp32) "parakeet-v3-fp32" else "parakeet-v3"), fp32)
        if (arguments.getString("downloadModels") == "true") models.install { message ->
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
        }
        assumeTrue("Download Parakeet in the app before running native inference test", models.ready())
        val youtube = arguments.getString("audioSample") == "youtube"
        val file = fixture(if (youtube) "youtube-smoke.wav" else "italian-smoke.wav")
        try {
            val audio = AudioDecoder.decode(context, Uri.fromFile(file))
            val began = System.nanoTime()
            val transcript = Parakeet.transcribe(audio, models)
            val elapsedMs = (System.nanoTime() - began) / 1_000_000
            val peakRssKiB = File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("VmHWM:") }?.trim()?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
            val text = transcript.words.joinToString(" ") { it.text }
            File(context.filesDir, "device-smoke.${models.precision}.words.json").writeText(transcript.json())
            val metrics = JSONObject()
                .put("audioDurationMs", audio.durationMs).put("inferenceMs", elapsedMs)
                .put("precision", models.precision).put("peakRssKiB", peakRssKiB)
                .put("audioSample", if (youtube) "YouTube UmZwQf5TV3c, 28–58 seconds" else "FLEURS Italian dev")
                .put("text", text).toString(2)
            File(context.filesDir, "device-smoke.${models.precision}.metrics.json").writeText(metrics)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$metrics\n") })
            assertTrue("Recognized: $text", transcript.words.size >= 10)
            if (!youtube) assertTrue("Recognized: $text", text.contains("settentrionale", ignoreCase = true))
            assertTrue(transcript.words.all { it.startMs >= 0 && it.endMs >= it.startMs })
            assertTrue(transcript.words.all { it.startMs <= audio.durationMs + 500 })
        } finally { file.delete() }
    }
}
