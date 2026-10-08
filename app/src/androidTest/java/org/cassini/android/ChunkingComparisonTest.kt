package org.cassini.android

import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Measures what cutting a recording into pieces costs in words, against one decode of the whole
 * recording and, where a reference text exists, against that. A measurement, not a pass/fail gate
 * on accuracy: it fails only when a mode loses most of the words or progress misbehaves.
 */
class ChunkingComparisonTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun fixture(name: String): PcmAudio {
        val file = File(context.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        try { return AudioDecoder.decode(context, Uri.fromFile(file)) } finally { file.delete() }
    }

    /** Lowercase words. A curly apostrophe and digit grouping ("4.892") are spelling, not recognition, so they are folded first. */
    private fun tokens(text: String) = text.lowercase().replace('\u2019', '\'').replace(Regex("(?<=\\p{N})[.,](?=\\p{N})"), "")
        .split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }

    private fun distance(a: List<String>, b: List<String>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val current = IntArray(b.size + 1)
            current[0] = i
            for (j in 1..b.size) current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            previous = current
        }
        return previous[b.size]
    }

    @Test fun cuttingModesAgainstTheWholeRecording() {
        val arguments = InstrumentationRegistry.getArguments()
        // Minutes of decoding per run: asked for by name, scripts/device-smoke.sh cutting org.cassini.android.ChunkingComparisonTest
        assumeTrue("Pass audioSample=cutting to measure cutting modes", arguments.getString("audioSample") == "cutting")
        val models = ModelStore(File(context.filesDir, "parakeet-v3"))
        if (arguments.getString("downloadModels") == "true") {
            models.install { }
            ModelStore.installVad(context.filesDir) { }
        }
        assumeTrue("Install Parakeet and the speech detector first", models.ready() && ModelStore.vadReady(context.filesDir))
        val detector = ModelStore.vadPath(context.filesDir)
        val available = instrumentation.context.assets.list("")!!.toSet()
        val reference = tokens(JSONObject(instrumentation.context.assets.open("italian-smoke.json").bufferedReader().use { it.readText() }).getString("reference"))
        val modes = linkedMapOf<String, (PcmAudio, (Parakeet.Progress) -> Unit) -> Transcript>(
            "whole" to { audio, _ -> Parakeet.transcribe(audio, models, null, cutting = Parakeet.Cutting.WHOLE) },
            "fixed-15s" to { audio, progress -> Parakeet.transcribe(audio, models, null, cutting = Parakeet.Cutting.FIXED, onProgress = progress) },
            "quiet-cuts" to { audio, progress -> Parakeet.transcribe(audio, models, null, onProgress = progress) },
            "speech-10s-tail" to { audio, progress -> Parakeet.transcribe(audio, models, detector, DecodePolicy(), onProgress = progress) },
            "speech-span-notail" to { audio, progress -> Parakeet.transcribe(audio, models, detector, DecodePolicy(preserveSpan = true, tailPaddingMs = 0), onProgress = progress) },
            "speech-span-context" to { audio, progress -> Parakeet.transcribe(audio, models, detector, DecodePolicy.PARAKEET_V3_REFERENCE, onProgress = progress) },
            "speech-span-tail" to { audio, progress -> Parakeet.transcribe(audio, models, detector, onProgress = progress) },
        )
        val report = JSONArray()
        for (name in listOf("italian-smoke.wav", "italian-smoke.m4a", "youtube-smoke.wav", "youtube-long.wav").filter { it in available }) {
            val audio = fixture(name)
            var whole = emptyList<String>()
            for ((mode, run) in modes) {
                val steps = ArrayList<Parakeet.Progress>()
                val began = System.nanoTime()
                var firstWordsMs = -1L
                val transcript = run(audio) {
                    steps += it
                    if (firstWordsMs < 0 && it.hasWords) firstWordsMs = (System.nanoTime() - began) / 1_000_000
                }
                val elapsedMs = (System.nanoTime() - began) / 1_000_000
                val words = tokens(transcript.words.joinToString(" ") { it.text })
                if (mode == "whole") whole = words
                val entry = JSONObject().put("audio", name).put("rate", audio.sampleRate).put("mode", mode).put("audioMs", audio.durationMs)
                    .put("elapsedMs", elapsedMs).put("words", words.size).put("fromWhole", distance(whole, words))
                    .put("steps", steps.size).put("firstWordsAfterMs", steps.firstOrNull { it.hasWords }?.elapsedMs ?: -1)
                    .put("firstWordsWaitMs", firstWordsMs)
                if (name.startsWith("italian-smoke")) entry.put("fromReference", distance(reference, words))
                entry.put("text", transcript.words.joinToString(" ") { it.text })
                entry.put("timed", transcript.words.joinToString(" ") { "${it.text}@${it.startMs}-${it.endMs}" })
                report.put(entry)
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nCUT ${entry.toString()}\n") })
                assertTrue("$name $mode kept ${words.size} of ${whole.size} words", words.size * 2 >= whole.size)
                // A forced cut lands inside this word at 133.58 s; decoded without context around the cut it came out as "batteria".
                if (name == "youtube-long.wav") assertTrue("$mode lost the word at the 133.58 s cut",
                    transcript.words.any { it.startMs in 132_500..134_500 && tokens(it.text) == listOf("batterica") })
                assertTrue(transcript.words.all { it.startMs >= 0 && it.endMs >= it.startMs && it.endMs <= audio.durationMs })
                if (mode != "whole") {
                    assertTrue("$name $mode reported no progress", steps.size >= 2)
                    assertTrue("$name $mode progress went backwards", steps.zipWithNext().all { (a, b) -> b.doneMs >= a.doneMs && b.elapsedMs >= a.elapsedMs })
                    assertEquals(audio.durationMs, steps.last().doneMs)
                }
            }
        }
        File(context.filesDir, "chunking-comparison.${models.precision}.json").writeText(report.toString(2))
    }
}
