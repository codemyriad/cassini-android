package org.cassini.android

import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Measures voiceprint similarity for the match thresholds in [Voiceprints.THRESHOLDS]. Logs every score. */
class VoiceprintCalibrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val model get() = VoiceprintModel.inFiles(context.filesDir)
    private fun log(message: String) { Log.i("CassiniVoice", message); println(message) }
    private fun hasAsset(name: String) = try { instrumentation.context.assets.open(name).close(); true } catch (_: java.io.IOException) { false }
    private fun fixture(name: String): PcmAudio {
        val file = File(context.cacheDir, "voice-" + name.substringAfterLast('/'))
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        try { return AudioDecoder.decode(context, Uri.fromFile(file)).let { PcmAudio(resampleTo16k(it.samples, it.sampleRate), 16000) } }
        finally { file.delete() }
    }
    private fun prints(audio: PcmAudio, turns: List<Triple<Long, Long, String>>): Map<String, Pair<FloatArray, Double>> {
        val ids = turns.map { it.third }.distinct()
        val words = turns.map { Word(it.third, it.first, it.second, "x") }.sortedBy { it.startMs }
        val windows = Voiceprints.select(turns.map { SpeakerTurn(it.first, it.second, ids.indexOf(it.third)) }, words, audio.sampleRate, audio.samples.size)
        return Voiceprints.compute(File(model.modelPath), audio, windows) { }
    }
    private fun summary(name: String, scores: List<Float>) {
        if (scores.isEmpty()) return
        val sorted = scores.sorted()
        log("$name: n=${sorted.size} min=%.3f median=%.3f max=%.3f".format(java.util.Locale.ROOT, sorted.first(), sorted[sorted.size / 2], sorted.last()))
    }

    @Test fun sameVoiceScoresAboveDifferentVoices() {
        assumeTrue("Install the voiceprint model first", model.ready())
        // Two halves of each single-voice fixture are the same speaker; the two fixtures are different speakers.
        val halves = listOf("italian-smoke.wav", "youtube-smoke.wav").flatMap { name ->
            val audio = fixture(name); val half = audio.durationMs / 2
            prints(audio, listOf(Triple(0L, half, "$name/1"), Triple(half, audio.durationMs, "$name/2"))).toList()
        }.toMap()
        assertEquals(4, halves.size)
        val same = mutableListOf<Float>(); val different = mutableListOf<Float>()
        val keys = halves.keys.toList()
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            val score = Voiceprints.cosine(halves.getValue(keys[i]).first, halves.getValue(keys[j]).first)
            log("fixture ${keys[i]} ~ ${keys[j]}: %.3f".format(java.util.Locale.ROOT, score))
            (if (keys[i].substringBefore('/') == keys[j].substringBefore('/')) same else different).add(score)
        }
        summary("fixture same", same); summary("fixture different", different)
        assertTrue(same.min() > different.max())
    }

    /** Private multi-speaker clips with per-participant ground truth; skipped when they are not present. */
    @Test fun meetingVoicesAcrossRecordings() {
        assumeTrue("Install the voiceprint model first", model.ready())
        assumeTrue("Add the private meeting fixtures first", hasAsset("meetings/index.json"))
        val index = JSONObject(instrumentation.context.assets.open("meetings/index.json").bufferedReader().readText()).getJSONArray("clips")
        // Speaker key is clip/name/half; halves within a clip measure same-recording similarity.
        val all = LinkedHashMap<String, FloatArray>()
        for (i in 0 until index.length()) {
            val clip = index.getJSONObject(i)
            val truth = JSONObject(instrumentation.context.assets.open("meetings/" + clip.getString("groundTruth")).bufferedReader().readText())
            val audio = fixture("meetings/" + clip.getString("wav"))
            val turns = truth.getJSONArray("turns").let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
                .map { Triple(it.getLong("startMs"), it.getLong("endMs"), it.getString("speaker")) }
            val began = System.nanoTime()
            for ((name, print) in prints(audio, turns)) all["${clip.getString("clip")}/$name/all"] = print.first
                .also { log("${clip.getString("clip")} $name: %.1f s of speech".format(java.util.Locale.ROOT, print.second)) }
            // Halves: the same person's speech split by time, each with a separate print.
            val split = turns.groupBy { it.third }.flatMap { (name, own) ->
                val middle = own.sumOf { it.second - it.first } / 2; var seen = 0L
                own.sortedBy { it.first }.map { turn -> seen += turn.second - turn.first; turn.copy(third = "$name/" + if (seen <= middle) "1" else "2") }
            }
            // Other speakers' turns must still be cut out: select treats every other id as another voice.
            for ((key, print) in prints(audio, split)) all["${clip.getString("clip")}/$key"] = print.first
            log("${clip.getString("clip")}: prints in ${(System.nanoTime() - began) / 1_000_000} ms")
        }
        val sameRecording = mutableListOf<Float>(); val sameAcross = mutableListOf<Float>(); val different = mutableListOf<Float>()
        val keys = all.keys.toList()
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            val (clipA, nameA, partA) = keys[i].split('/'); val (clipB, nameB, partB) = keys[j].split('/')
            val score = Voiceprints.cosine(all.getValue(keys[i]), all.getValue(keys[j]))
            when {
                clipA == clipB && nameA == nameB && partA != "all" && partB != "all" -> sameRecording
                clipA != clipB && nameA == nameB && partA == "all" && partB == "all" -> sameAcross
                nameA != nameB && partA == "all" && partB == "all" -> different
                else -> null
            }?.let { it.add(score); log("${keys[i]} ~ ${keys[j]}: %.3f".format(java.util.Locale.ROOT, score)) }
        }
        summary("meeting same recording", sameRecording); summary("meeting same person across recordings", sameAcross)
        summary("meeting different people", different)
        // Identification as the app does it: each speaker against people enrolled from the other recordings.
        val wholes = all.filterKeys { it.endsWith("/all") }.mapKeys { it.key.substringBeforeLast('/') }
        val trials = wholes.map { (key, print) ->
            val (clip, name) = key.split('/')
            val gallery = wholes.filterKeys { !it.startsWith("$clip/") }.entries.groupBy({ it.key.substringAfter('/') }, { it.value })
                .mapValues { (_, prints) -> Voiceprints.normalised(FloatArray(prints[0].size) { d -> prints.sumOf { it[d].toDouble() }.toFloat() })!! }
            val ranked = gallery.mapValues { Voiceprints.cosine(print, it.value) }.entries.sortedByDescending { it.value }
            log("identify $key: " + ranked.joinToString { "${it.key}=%.3f".format(java.util.Locale.ROOT, it.value) })
            Triple(name in gallery, ranked[0].key == name, ranked[0].value to ranked[0].value - (ranked.getOrNull(1)?.value ?: -1f))
        }
        // A stranger: the best score among everyone else once the speaker's own voice is not enrolled.
        val strangers = wholes.map { (key, print) ->
            val (clip, name) = key.split('/')
            wholes.filterKeys { !it.startsWith("$clip/") && it.substringAfter('/') != name }.values.maxOf { Voiceprints.cosine(print, it) }
        }
        summary("stranger best score", strangers)
        for (auto in listOf(0.55f, 0.60f, 0.65f, 0.70f)) for (margin in listOf(0.0f, 0.05f, 0.10f, 0.15f)) {
            val applied = trials.filter { it.third.first >= auto && it.third.second >= margin }
            log("auto=%.2f margin=%.2f: applied ${applied.size}/${trials.count { it.first }} enrolled, wrong ${applied.count { !it.second }}"
                .format(java.util.Locale.ROOT, auto, margin))
        }
        val t = Voiceprints.THRESHOLDS.getValue(VoiceprintModel.model.sha256)
        log("auto=${t.auto} suggest=${t.suggest} margin=${t.margin}: " +
            "different>=suggest ${different.count { it >= t.suggest }}, different>=auto ${different.count { it >= t.auto }}, " +
            "across>=auto ${sameAcross.count { it >= t.auto }}/${sameAcross.size}, across>=suggest ${sameAcross.count { it >= t.suggest }}/${sameAcross.size}")
        assertTrue("No two different people may auto-match", different.none { it >= t.auto })
        assertTrue("A stranger must not auto-match", strangers.none { it >= t.auto })
        assertTrue("An applied match must be the right person", trials.none { !it.second && it.third.first >= t.auto && it.third.second >= t.margin })
    }
}
