package org.cassini.core.checkpoint

import org.cassini.core.asr.IncrementalTranscript
import org.cassini.core.format.Transcript
import org.cassini.core.models.ModelCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `checkpoint.json` and its `names.json` that a real 0.1.0 job left on emulator-5554 (a 60-second import stopped
 * after its first 28-second window, its speaker named "Ada Example"), read and written by the typed codec.
 * The file is version 1, which the app no longer resumes (D51): [raw] is it as stored, and [golden] is the same job
 * as version 2 (with an empty word log), the data every codec check uses.
 * Equality is semantic: the JVM org.json orders members differently from Android's, so the test compares key sets
 * and values, not bytes. The golden comes from 0ebea43, which omits an empty transcript language.
 */
class CheckpointGoldenTest {
    private fun resource(name: String): JSONObject {
        val stream = checkNotNull(javaClass.getResourceAsStream("/checkpoint/golden-v1/$name")) { "Missing $name" }
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    private val raw = resource("checkpoint.json")
    private val golden = JSONObject(raw.toString()).put("version", 2).put("wordLog", WordLogRef.EMPTY.json())
    private val state = CheckpointState.decode(golden)

    /** What the golden itself says it was made from, and the lengths its own counters need on disk. */
    private fun expected(
        source: String = golden.getString("source"),
        signature: String = golden.getString("signature"),
        clipsBytes: Long = golden.getInt("clipSamples") * Float.SIZE_BYTES.toLong(),
        audioBytes: Long = golden.getJSONObject("encoder").getLong("length"),
    ) = CheckpointState.Expected(
        source = source,
        signature = signature,
        modelRevision = golden.getString("model"),
        speakersSha256 = golden.getString("speakers"),
        clipsBytes = clipsBytes,
        audioBytes = audioBytes,
    )

    @Test fun theFileHasExactlyTheVersionOneMembers() {
        assertEquals(CheckpointState.KEYS - "wordLog", raw.keys().asSequence().toSet())
        assertEquals(
            setOf(
                "version", "source", "signature", "model", "speakers", "recognition", "encoder", "diarizer", "speakerPrefix",
                "speakerFailure", "clipSamples", "voiceClips", "positionSamples", "doneMs", "durationMs", "elapsedMs",
            ),
            CheckpointState.KEYS - "wordLog",
        )
        assertEquals(1, raw.getInt("version"))
        val recognition = golden.getJSONObject("recognition")
        assertEquals(setOf("inferenceMs", "chunks"), recognition.keys().asSequence().toSet())
        val chunks = recognition.getJSONObject("chunks")
        assertEquals(setOf("start", "end", "window", "words"), chunks.keys().asSequence().toSet())
        assertEquals(
            setOf("history", "frame", "samples", "pending", "length", "serial", "sequence", "granule", "packet"),
            golden.getJSONObject("encoder").keys().asSequence().toSet(),
        )
        assertEquals(setOf("cache", "elapsedMs", "turns"), golden.getJSONObject("diarizer").keys().asSequence().toSet())
        val stoppedAfterTheFirstWindow = golden.getLong("doneMs") in 28_000 until golden.getLong("durationMs")
        assertTrue("Stopped after the first window", stoppedAfterTheFirstWindow)
    }

    /** A version 1 file, as 0.1.0 and early 0.2 development builds wrote it, is not resumed (D51). */
    @Test fun theVersionOneFileIsNotResumed() {
        assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(raw, expected()) }
    }

    /** The codec writes version 2: every member and value of 0.1.0, then an empty word log. */
    @Test fun reEncodingTheDecodedStateKeepsEveryMemberAndValue() {
        val encoded = JSONObject(CheckpointState.encode(state).toString())
        assertEquals(CheckpointState.KEYS, encoded.keys().asSequence().toSet())
        assertEquals(2, encoded.getInt("version"))
        assertEquals(WordLogRef.EMPTY, CheckpointState.wordLog(encoded))
        assertEquals(plain(JSONObject(golden.toString())), plain(encoded))
        assertEquals(state.recognition.chunks.words, CheckpointState.decode(encoded).recognition.chunks.words)
    }

    @Test fun theGoldenValidatesAgainstItsOwnIdentity() {
        CheckpointState.validate(golden, expected())
        CheckpointState.validate(state, expected())
        assertEquals("0.1.0 made it with Parakeet TDT 0.6B v3", PARAKEET_V3_REVISION, state.model)
        assertEquals("0.1.0's diarizer export", DIARIZER_010, state.speakers)
        assertEquals("0.1.0 keys a checkpoint by its source uri", DIRECTORY_KEY, CheckpointState.key(state.source))
    }

    /** Words of v3 and words of Parakeet Ultra never mix in one transcript: this build does not resume the golden. */
    @Test fun aCheckpointOfParakeetV3IsNotResumedWithUltra() {
        val ultra = CheckpointState.Expected(
            source = state.source,
            signature = state.signature,
            modelRevision = ModelCatalog.PARAKEET_REVISION,
            speakersSha256 = ModelCatalog.diarizationModel.sha256,
            clipsBytes = Long.MAX_VALUE,
            audioBytes = Long.MAX_VALUE,
        )
        assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(golden, ultra) }
        assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(state, ultra) }
    }

    @Test fun aChangedIdentityOrShorterFilesRejectTheGolden() {
        val changed = listOf(
            expected(source = "file:///elsewhere.opus"),
            expected(signature = state.signature.substringBefore(':') + ":0"),
            expected(clipsBytes = state.clipSamples * Float.SIZE_BYTES.toLong() - 1),
            expected(audioBytes = state.encoder!!.length - 1),
        )
        changed.forEach { expected ->
            assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(golden, expected) }
            assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(state, expected) }
        }
        val otherModel = CheckpointState.Expected(
            source = state.source,
            signature = state.signature,
            modelRevision = "0".repeat(40),
            speakersSha256 = state.speakers,
            clipsBytes = Long.MAX_VALUE,
            audioBytes = Long.MAX_VALUE,
        )
        assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(state, otherModel) }
    }

    @Test fun recognitionRestoresWhereTheJobStopped() {
        val restored = IncrementalTranscript(state.recognition.chunks) { _, _ -> error("A restore must not decode") }
        assertEquals(golden.getLong("positionSamples"), restored.positionSamples)
        assertEquals(golden.getLong("doneMs"), restored.doneMs)
        assertEquals(golden.getLong("positionSamples"), state.positionSamples)
        assertEquals(golden.getLong("doneMs"), state.doneMs)
        assertEquals(golden.getJSONObject("recognition").getLong("inferenceMs"), state.recognition.inferenceMs)
        val chunks = golden.getJSONObject("recognition").getJSONObject("chunks")
        val words = Transcript.fromJson(chunks.getJSONObject("words").toString()).words
        assertEquals(words, state.recognition.chunks.words)
        assertTrue(words.isNotEmpty())
        assertTrue(words.any { it.text.contains("settentrionale", ignoreCase = true) })
        assertTrue(words.all { it.speaker.startsWith(state.speakerPrefix) && it.endMs <= state.doneMs })
        assertTrue(state.recognition.chunks.window.size in 1..IncrementalTranscript.WINDOW_SAMPLES)
    }

    /** The v0.1.0 tag wrote `"language":"it"` into the word body; the codec reads the same words with it. */
    @Test fun aWordBodyWithTheTagsLanguageMemberDecodesTheSameWords() {
        val tagged = JSONObject(golden.toString())
        tagged.getJSONObject("recognition").getJSONObject("chunks").getJSONObject("words").put("language", "it")
        val chunks = tagged.getJSONObject("recognition").getJSONObject("chunks")
        assertEquals("it", Transcript.fromJson(chunks.getJSONObject("words").toString()).language)
        val decoded = CheckpointState.decode(tagged)
        assertEquals(state.recognition.chunks.words, decoded.recognition.chunks.words)
        val restored = IncrementalTranscript(decoded.recognition.chunks) { _, _ -> error("A restore must not decode") }
        assertEquals(state.positionSamples, restored.positionSamples)
        assertEquals(state.doneMs, restored.doneMs)
    }

    @Test fun encoderDiarizerAndClipStateDecode() {
        val encoder = golden.getJSONObject("encoder")
        val typed = state.encoder!!
        assertEquals(encoder.getLong("samples"), typed.samples)
        assertEquals(encoder.getLong("length"), typed.length)
        assertEquals(encoder.getInt("pending"), typed.pending)
        assertEquals(encoder.getInt("serial"), typed.serial)
        assertEquals(encoder.getInt("sequence"), typed.sequence)
        assertEquals(encoder.getLong("granule"), typed.granule)
        assertEquals(CheckpointState.HISTORY_FLOATS, typed.history!!.size)
        assertEquals(CheckpointState.FRAME_FLOATS, typed.frame.size)
        assertTrue(typed.packet!!.isNotEmpty())
        assertEquals(state.positionSamples, typed.samples)
        assertTrue(typed.pending in 0 until CheckpointState.FRAME_FLOATS)

        val diarizer = state.diarizer!!
        assertEquals(golden.getJSONObject("diarizer").getJSONArray("turns").length(), diarizer.turns.size)
        assertEquals(golden.getJSONObject("diarizer").getLong("elapsedMs"), diarizer.elapsedMs)
        assertTrue(diarizer.cache.isNotEmpty())
        assertTrue(diarizer.turns.isNotEmpty())
        assertTrue(diarizer.turns.all { it.startMs < it.endMs && it.speaker in 0..7 })

        assertEquals(golden.getString("speakerPrefix"), state.speakerPrefix)
        assertEquals(golden.getInt("clipSamples"), state.clipSamples)
        val clips = state.voiceClips!!
        assertEquals(setOf(state.speakerPrefix + "1"), clips.keys)
        assertTrue(clips.values.flatten().all { it.startSample < it.endSample && it.endSample <= state.clipSamples })
    }

    @Test fun draftNamesAndTheirGenerationRoundTrip() {
        val stored = resource("names.json")
        assertEquals(CheckpointNames.KEYS, stored.keys().asSequence().toSet())
        val names = CheckpointNames.decode(stored)
        val speaker = state.speakerPrefix + "1"
        assertEquals(mapOf(speaker to "Ada Example"), names.labels)
        assertEquals(emptyMap<String, String>(), names.merges)
        assertEquals(mapOf(speaker to ""), names.remembered)
        assertEquals(emptyMap<String, String>(), names.automatic)
        assertEquals(4L, names.generation)
        assertEquals(plain(stored), plain(JSONObject(CheckpointNames.encode(names).toString())))
    }

    /** JSON as nested maps and lists, so member order does not matter. */
    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        else -> value
    }

    private companion object {
        /** The directory 0.1.0 stored this checkpoint in: the SHA-256 of its `source` string. */
        const val DIRECTORY_KEY = "837a3ea3f0eaa764c13133771ad687859511481d587d90a0612ff25b955a275b"

        /** The revision of Parakeet TDT 0.6B v3 INT8, the speech model of 0.1.0, which made the golden. */
        const val PARAKEET_V3_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"

        /** The SHA-256 of 0.1.0's diarizer export, which made the golden; the model host's export replaced it. */
        const val DIARIZER_010 = "01d1f893f394cc0418ae78425d216ed62c334c0125e650135332a7a8e595f168"
    }
}
