package org.cassini.core.checkpoint

import org.cassini.core.asr.IncrementalTranscript
import org.cassini.core.audio.PcmFile
import org.cassini.core.format.OggOpus
import org.cassini.core.format.Transcript
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.SpeakerBudget
import org.cassini.core.speakers.VoiceClipCache
import org.cassini.core.testing.FixtureTree
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

/**
 * The typed codec against the checkpoint a 0.1.0 job left on a device (fixtures/filesdir/v0.1.0-basic). Equality is
 * semantic: the JVM org.json orders members differently from Android's, whose byte order the device test checks.
 */
class CheckpointStateTest {
    @get:Rule val folder = TemporaryFolder()

    private class Captured(val source: String, val directory: File) {
        val json get() = JSONObject(File(directory, "checkpoint.json").readText())
        val names get() = JSONObject(File(directory, "names.json").readText())
        val audio get() = File(directory, "audio.opus.part")
        val clips get() = File(directory, "clips.pcm")
        fun expected(
            signature: String = CheckpointState.signature(File(URI(source))),
            model: String = ModelCatalog.PARAKEET_REVISION,
            speakers: String = ModelCatalog.diarizationModel.sha256,
        ) = CheckpointState.Expected(source, signature, model, speakers, clips.length(), audio.length())
    }

    private fun captured(): Captured {
        val tree = FixtureTree.materialise("v0.1.0-basic", folder.newFolder("files"))
        val (source, directory) = tree.checkpoints.entries.single()
        assertEquals(CheckpointState.key(source), directory.name)
        return Captured(source, directory)
    }

    @Test fun theCapturedCheckpointValidatesAsZeroOneZeroLoadsIt() {
        val captured = captured()
        CheckpointState.validate(captured.json, captured.expected())
        CheckpointState.validate(CheckpointState.decode(captured.json), captured.expected())
    }

    @Test fun theCapturedCheckpointDecodesToTypedState() {
        val captured = captured()
        val state = CheckpointState.decode(captured.json)
        assertEquals(1, state.version)
        assertEquals(captured.source, state.source)
        assertEquals(ModelCatalog.PARAKEET_REVISION, state.model)
        assertEquals(ModelCatalog.diarizationModel.sha256, state.speakers)
        assertTrue("Stopped after the first window", state.doneMs in 28_000 until state.durationMs)
        assertEquals(4776L, state.recognition.inferenceMs)
        val chunks = state.recognition.chunks
        assertEquals(state.positionSamples, chunks.start + chunks.window.size)
        assertTrue(chunks.words.any { it.text.contains("settentrionale", ignoreCase = true) })
        assertTrue(chunks.words.all { it.speaker.startsWith(state.speakerPrefix) && it.endMs <= state.doneMs })
        val encoder = state.encoder!!
        assertEquals(CheckpointState.HISTORY_FLOATS, encoder.history!!.size)
        assertEquals(CheckpointState.FRAME_FLOATS, encoder.frame.size)
        assertEquals(state.positionSamples, encoder.samples)
        assertTrue(encoder.pending in 0 until CheckpointState.FRAME_FLOATS && encoder.packet!!.isNotEmpty())
        assertTrue(encoder.length <= captured.audio.length())
        captured.audio.inputStream().use { OggOpus.headers(it) }
        val diarizer = state.diarizer!!
        assertTrue(diarizer.cache.size in 1..SpeakerBudget.DIARIZER_STATE_FLOATS)
        assertTrue(diarizer.turns.isNotEmpty() && diarizer.turns.all { it.startMs < it.endMs && it.speaker in 0..7 })
        assertEquals(false, state.speakerFailure)
        assertEquals(setOf(state.speakerPrefix + "1"), state.voiceClips!!.keys)
        PcmFile(captured.clips, deleteOnClose = false).use { clips ->
            assertEquals(state.clipSamples, clips.sampleCount)
            val cache = VoiceClipCache(clips, CheckpointState.json(state.voiceClips!!))
            assertEquals(state.voiceClips, cache.windows)
        }
    }

    @Test fun encodingWritesBackExactlyTheMembersItRead() {
        val captured = captured()
        val json = captured.json
        val encoded = CheckpointState.encode(CheckpointState.decode(json))
        assertEquals(CheckpointState.KEYS, encoded.keys().asSequence().toSet())
        assertEquals(CheckpointState.KEYS, json.keys().asSequence().toSet())
        assertEquals(plain(json), plain(JSONObject(encoded.toString())))
    }

    @Test fun recognitionRestoresWhereTheJobStopped() {
        val captured = captured()
        val state = CheckpointState.decode(captured.json)
        val restored = IncrementalTranscript(state.recognition.chunks) { _, _ -> error("A restore must not decode") }
        assertEquals(state.positionSamples, restored.positionSamples)
        assertEquals(state.doneMs, restored.doneMs)
        val again = CheckpointState.json(restored.checkpoint())
        assertEquals(
            plain(captured.json.getJSONObject("recognition").getJSONObject("chunks")),
            plain(JSONObject(again.toString()))
        )
    }

    @Test fun theReleasedTagsLanguageMemberReads() {
        val json = captured().json
        json.getJSONObject("recognition").getJSONObject("chunks").getJSONObject("words").put("language", "it")
        val state = CheckpointState.decode(JSONObject(json.toString()))
        assertTrue(state.recognition.chunks.words.isNotEmpty())
    }

    @Test fun eachValidationRuleRejects() {
        val captured = captured()
        val json = captured.json
        fun rejects(expected: CheckpointState.Expected = captured.expected(), change: (JSONObject) -> Unit = {}) {
            val copy = JSONObject(json.toString()).also(change)
            assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(copy, expected) }
            assertThrows(
                IllegalArgumentException::class.java
            ) { CheckpointState.validate(CheckpointState.decode(copy), expected) }
        }
        rejects { it.put("version", 2) }
        rejects { it.put("source", "file:///elsewhere.m4a") }
        rejects(captured.expected(signature = "1:2"))
        rejects(captured.expected(model = "another revision"))
        rejects(captured.expected(speakers = "another sha"))
        rejects { it.put("clipSamples", CheckpointState.MAX_CLIP_SAMPLES + 1) }
        rejects { it.put("clipSamples", -1) }
        rejects { it.put("clipSamples", (captured.clips.length() / 4 + 1).toInt()) }
        rejects { it.put("positionSamples", -1) }
        rejects { it.put("doneMs", 2 * 60 * 60 * 1000L + 1) }
        rejects { it.getJSONObject("encoder").put("length", captured.audio.length() + 1) }
        // A job without an encoder (an Opus source) does not check the audio part.
        CheckpointState.validate(JSONObject(json.toString()).apply { remove("encoder") }, captured.expected())
    }

    @Test fun aChangedSourceInvalidatesTheCheckpoint() {
        val captured = captured()
        val source = File(URI(captured.source))
        assertTrue(source.setLastModified(source.lastModified() + 1000))
        assertThrows(
            IllegalArgumentException::class.java
        ) { CheckpointState.validate(captured.json, captured.expected()) }
    }

    @Test fun aNonFileSourceHasTheZeroOneZeroNullSignature() {
        assertEquals("null:null", CheckpointState.signature(null))
    }

    @Test fun draftNamesAndTheirGenerationLoad() {
        val captured = captured()
        val prefix = captured.json.getString("speakerPrefix")
        val names = CheckpointNames.decode(captured.names)
        assertEquals(mapOf("${prefix}1" to "Ada Example"), names.labels)
        assertEquals(emptyMap<String, String>(), names.merges)
        assertEquals(mapOf("${prefix}1" to ""), names.remembered)
        assertEquals(emptyMap<String, String>(), names.automatic)
        assertEquals(4L, names.generation)
        val encoded = CheckpointNames.encode(names)
        assertEquals(CheckpointNames.KEYS, encoded.keys().asSequence().toSet())
        assertEquals(plain(captured.names), plain(JSONObject(encoded.toString())))
    }

    @Test fun namesWrittenBeforeTheExtrasExistedRead() {
        val names = CheckpointNames.decode(
            JSONObject().put("labels", JSONObject().put("a", "Ada")).put("merges", JSONObject())
        )
        assertEquals(CheckpointNames(mapOf("a" to "Ada"), emptyMap()), names)
    }

    @Test fun encoderAndDiarizerStateRoundTripBitForBit() {
        val state = CheckpointState.decode(captured().json)
        val encoder = CheckpointState.encoder(JSONObject(CheckpointState.json(state.encoder!!).toString()))
        assertArrayEquals(state.encoder!!.history, encoder.history, 0f)
        assertArrayEquals(state.encoder!!.packet, encoder.packet)
        assertEquals(state.encoder!!.granule, encoder.granule)
        val diarizer = CheckpointState.diarizer(JSONObject(CheckpointState.json(state.diarizer!!).toString()))
        assertArrayEquals(state.diarizer!!.cache, diarizer.cache, 0f)
        assertEquals(state.diarizer!!.turns, diarizer.turns)
    }

    @Test fun anOversizedDiarizerCacheIsRefused() {
        val json = JSONObject().put("cache", CheckpointData.floats(FloatArray(SpeakerBudget.DIARIZER_STATE_FLOATS + 1)))
            .put("elapsedMs", 0).put("turns", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { CheckpointState.diarizer(json) }
    }

    @Test fun wordsInTheCheckpointAreTheCassiniWordsFormat() {
        val chunks = captured().json.getJSONObject("recognition").getJSONObject("chunks")
        assertNotNull(Transcript.fromJson(chunks.getJSONObject("words").toString()))
    }

    /** JSON as nested maps and lists, so member order does not matter. */
    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        else -> value
    }
}
