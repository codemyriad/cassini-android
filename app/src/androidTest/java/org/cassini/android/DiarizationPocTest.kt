package org.cassini.android

import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Proof-of-concept measurements for speaker diarization. Models and the multi-speaker clip are not
 * bundled; push them first (scripts/diarization-poc.sh) to a directory the app can read.
 * Results go to logcat under CassiniDiar.
 */
class DiarizationPocTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val directory = File(InstrumentationRegistry.getArguments().getString("diarDir") ?: "/data/local/tmp/cassini-diar")
    private val segmentation get() = File(directory, "segmentation.int8.onnx").path
    private val embedding get() = File(directory, "embedding.onnx").path

    private fun log(message: String) { Log.i("CassiniDiar", message); println(message) }
    private fun peakRssMb() = File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }.filter { it.isDigit() }.toLong() / 1024

    private fun load(file: File): PcmAudio {
        val copy = File(context.cacheDir, file.name).also { file.copyTo(it, overwrite = true) }
        try { return AudioDecoder.decode(context, Uri.fromFile(copy)) } finally { copy.delete() }
    }

    private fun assets(name: String): PcmAudio {
        val file = File(context.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        try { return AudioDecoder.decode(context, Uri.fromFile(file)) } finally { file.delete() }
    }

    private fun ready() = File(segmentation).canRead() && File(embedding).canRead()

    @Test fun fourSpeakerClipTimeAndMemory() {
        val clip = File(directory, "four-speakers.wav")
        assumeTrue("Push the diarization models and clip first", ready() && clip.canRead())
        val audio = load(clip)
        for ((label, count) in listOf("auto" to -1, "known-4" to 4)) {
            val began = System.nanoTime()
            val turns = Diarization.turns(audio, segmentation, embedding, speakerCount = count)
            val elapsed = (System.nanoTime() - began) / 1_000_000
            log("four-speakers $label: ${audio.durationMs} ms audio, $elapsed ms (${"%.2f".format(audio.durationMs / elapsed.toDouble())}x realtime), " +
                "${turns.size} turns, ${turns.map { it.speaker }.toSet().size} speakers, peak RSS ${peakRssMb()} MB")
            turns.forEach { log("  ${it.startMs}-${it.endMs} speaker ${it.speaker}") }
            assertTrue(turns.isNotEmpty())
        }
    }

    /** Two different recorded voices joined end to end, so the right answer is known: one change of speaker. */
    @Test fun transcribeThenAttributeTwoVoices() {
        assumeTrue("Push the diarization models first", ready())
        val models = ModelStore(File(context.filesDir, "parakeet-v3"))
        assumeTrue("Install Parakeet and the speech detector first", models.ready() && ModelStore.vadReady(context.filesDir))
        val first = assets("italian-smoke.wav"); val second = assets("youtube-smoke.wav")
        val gapSamples = 16000
        val joined = PcmAudio(first.samples + FloatArray(gapSamples) + second.samples, 16000)
        val boundaryMs = first.durationMs + 1000

        val asrBegan = System.nanoTime()
        val transcript = Parakeet.transcribe(joined, models, ModelStore.vadPath(context.filesDir))
        val asrMs = (System.nanoTime() - asrBegan) / 1_000_000
        val diarBegan = System.nanoTime()
        val turns = Diarization.turns(joined, segmentation, embedding)
        val diarMs = (System.nanoTime() - diarBegan) / 1_000_000
        val words = Diarization.assign(transcript.words, turns)
        log("two voices: ${joined.durationMs} ms audio, asr $asrMs ms, diarization $diarMs ms (${"%.0f".format(diarMs * 100.0 / asrMs)}% of asr), peak RSS ${peakRssMb()} MB")
        turns.forEach { log("  turn ${it.startMs}-${it.endMs} speaker ${it.speaker}") }
        val wrong = words.filter { (it.startMs + it.endMs) / 2 < boundaryMs != (it.speaker == words.first().speaker) }
        log("  ${words.size} words, ${words.map { it.speaker }.toSet().size} speakers, ${wrong.size} on the wrong side of the join")
        log("  " + words.joinToString(" ") { "${it.text}/${it.speaker.removePrefix("spk_")}" })
        assertTrue(words.isNotEmpty())
    }

    /** A real two-person conversation recorded in the app; prints each word with its speaker for comparison with a reference. */
    @Test fun transcribeThenAttributeRealConversation() {
        val clip = File(directory, "conversation.wav")
        assumeTrue("Push the models and conversation.wav first", ready() && clip.canRead())
        val models = ModelStore(File(context.filesDir, "parakeet-v3"))
        assumeTrue("Install Parakeet and the speech detector first", models.ready() && ModelStore.vadReady(context.filesDir))
        val audio = load(clip)
        val transcript = Parakeet.transcribe(audio, models, ModelStore.vadPath(context.filesDir))
        for ((label, count) in listOf("auto" to -1, "known-2" to 2)) {
            val began = System.nanoTime()
            val turns = Diarization.turns(audio, segmentation, embedding, speakerCount = count)
            val elapsed = (System.nanoTime() - began) / 1_000_000
            val words = Diarization.assign(transcript.words, turns)
            log("conversation $label: ${audio.durationMs} ms audio, diarization $elapsed ms, ${turns.size} turns, ${words.map { it.speaker }.toSet().size} speakers, peak RSS ${peakRssMb()} MB")
            turns.forEach { log("  turn ${it.startMs}-${it.endMs} speaker ${it.speaker}") }
            var line = ""; var current = ""
            for (word in words) {
                if (word.speaker != current) { if (line.isNotEmpty()) log("  [$current] $line"); current = word.speaker; line = "" }
                line += (if (line.isEmpty()) "" else " ") + word.text + "@" + word.startMs
            }
            log("  [$current] $line")
        }
    }
}
