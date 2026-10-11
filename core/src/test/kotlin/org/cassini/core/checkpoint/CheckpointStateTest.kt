package org.cassini.core.checkpoint

import org.cassini.core.asr.IncrementalState
import org.cassini.core.asr.IncrementalTranscript
import org.cassini.core.audio.Limits
import org.cassini.core.format.Word
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.PrintWindow
import org.cassini.core.speakers.SpeakerBudget
import org.cassini.core.speakers.SpeakerTurn
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/** checkpoint.json version 2 and names.json through the typed codec, and the rules a checkpoint must pass to resume. */
class CheckpointStateTest {
    @get:Rule val folder = TemporaryFolder()

    private val prefix = "diar_0123_"
    private val words = listOf(Word("${prefix}1", 1308, 1548, "La"), Word("${prefix}1", 1548, 1788, "parte"))

    /** A job stopped after its first 28 s window over [source], with the files a checkpoint directory holds. */
    private inner class Job {
        val source: File = folder.newFile("source.m4a").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val uri = source.toURI().toString()
        val audio: File = folder.newFile("audio.opus.part").apply { writeBytes(ByteArray(2000)) }
        val clips: File = folder.newFile("clips.pcm").also { clips ->
            RandomAccessFile(clips, "rw").use { it.setLength(16_000 * 4L) }
        }
        val state = CheckpointState(
            source = uri,
            signature = CheckpointState.signature(source),
            model = ModelCatalog.PARAKEET_REVISION,
            speakers = ModelCatalog.diarizationModel.sha256,
            recognition = RecognitionState(
                4776,
                IncrementalState(416_000, 448_000, FloatArray(32_000) { it / 32_000f }, words),
            ),
            encoder = EncoderState(
                history = FloatArray(CheckpointState.HISTORY_FLOATS) { -it / 640f },
                frame = FloatArray(CheckpointState.FRAME_FLOATS) { it / 320f },
                samples = 448_000,
                pending = 192,
                length = audio.length(),
                serial = -767_747_305,
                sequence = 1562,
                granule = 1_498_560,
                packet = byteArrayOf(-72, -1, -2),
            ),
            diarizer = DiarizerState(FloatArray(1000) { it.toFloat() }, 2458, listOf(SpeakerTurn(1290, 15_019, 0))),
            speakerPrefix = prefix,
            speakerFailure = false,
            clipSamples = 16_000,
            voiceClips = mapOf("${prefix}1" to listOf(PrintWindow(0, 8000), PrintWindow(8000, 16_000))),
            positionSamples = 448_000,
            doneMs = 28_000,
            durationMs = 59_968,
            elapsedMs = 18_754,
        )

        fun expected(signature: String = CheckpointState.signature(source), wordLogBytes: Long = 0) =
            CheckpointState.Expected(
                uri,
                signature,
                ModelCatalog.PARAKEET_REVISION,
                ModelCatalog.diarizationModel.sha256,
                clips.length(),
                audio.length(),
                wordLogBytes,
            )
    }

    /** The JSON as stored and read back: through text, as the file holds it. */
    private fun stored(state: CheckpointState) = JSONObject(CheckpointState.encode(state).toString())

    @Test fun encodingWritesExactlyTheVersionTwoMembers() {
        val json = stored(Job().state)
        assertEquals(CheckpointState.KEYS_V2, json.keys().asSequence().toSet())
        assertEquals(CheckpointState.KEYS + "wordLog", CheckpointState.KEYS_V2)
        assertEquals(2, json.getInt("version"))
        assertEquals(WordLogRef.EMPTY, CheckpointState.wordLog(json))
        assertEquals(setOf("inferenceMs", "chunks"), json.getJSONObject("recognition").keys().asSequence().toSet())
        val chunks = json.getJSONObject("recognition").getJSONObject("chunks")
        assertEquals(setOf("start", "end", "window", "words"), chunks.keys().asSequence().toSet())
        assertEquals("cassini.words.v1", chunks.getJSONObject("words").getString("format"))
        assertEquals(
            setOf("history", "frame", "samples", "pending", "length", "serial", "sequence", "granule", "packet"),
            json.getJSONObject("encoder").keys().asSequence().toSet(),
        )
        assertEquals(setOf("cache", "elapsedMs", "turns"), json.getJSONObject("diarizer").keys().asSequence().toSet())
        val turn = json.getJSONObject("diarizer").getJSONArray("turns")[0]
        assertEquals(JSONArray(listOf(1290, 15_019, 0)).toString(), turn.toString())
        assertEquals("[[0,8000],[8000,16000]]", json.getJSONObject("voiceClips").getJSONArray("${prefix}1").toString())
    }

    @Test fun decodingRestoresEveryMemberBitForBit() {
        val state = Job().state
        val back = CheckpointState.decode(stored(state))
        assertEquals(
            listOf(state.source, state.signature, state.model, state.speakers, state.speakerPrefix, state.version),
            listOf(back.source, back.signature, back.model, back.speakers, back.speakerPrefix, back.version),
        )
        assertEquals(
            listOf(state.clipSamples.toLong(), state.positionSamples, state.doneMs, state.durationMs, state.elapsedMs),
            listOf(back.clipSamples.toLong(), back.positionSamples, back.doneMs, back.durationMs, back.elapsedMs),
        )
        assertEquals(state.speakerFailure, back.speakerFailure)
        assertEquals(state.voiceClips, back.voiceClips)
        assertEquals(state.recognition.inferenceMs, back.recognition.inferenceMs)
        assertArrayEquals(state.recognition.chunks.window, back.recognition.chunks.window, 0f)
        assertEquals(words, back.recognition.chunks.words)
        val encoder = back.encoder!!
        assertArrayEquals(state.encoder!!.history, encoder.history, 0f)
        assertArrayEquals(state.encoder!!.frame, encoder.frame, 0f)
        assertArrayEquals(state.encoder!!.packet, encoder.packet)
        assertEquals(listOf(448_000L, 2000L, 1_498_560L), listOf(encoder.samples, encoder.length, encoder.granule))
        assertEquals(listOf(192, -767_747_305, 1562), listOf(encoder.pending, encoder.serial, encoder.sequence))
        assertArrayEquals(state.diarizer!!.cache, back.diarizer!!.cache, 0f)
        assertEquals(state.diarizer!!.turns, back.diarizer!!.turns)
        assertEquals(2458L, back.diarizer!!.elapsedMs)
    }

    @Test fun aJobWithoutEncoderOrDiarizerOmitsThem() {
        val state = Job().state.copy(encoder = null, diarizer = null, voiceClips = null, speakerFailure = true)
        val json = stored(state)
        val absent = setOf("encoder", "diarizer", "voiceClips")
        assertEquals(CheckpointState.KEYS_V2 - absent, json.keys().asSequence().toSet())
        val back = CheckpointState.decode(json)
        assertEquals(null, back.encoder)
        assertEquals(null, back.diarizer)
        assertTrue(back.speakerFailure)
    }

    @Test fun anEncoderWrittenWithoutHistoryOrPacketReads() {
        val json = CheckpointState.json(Job().state.encoder!!).put("history", "").put("packet", "")
        val encoder = CheckpointState.encoder(json)
        assertEquals(null, encoder.history)
        assertEquals(null, encoder.packet)
    }

    @Test fun recognitionRestoresWhereTheJobStopped() {
        val json = stored(Job().state)
        val state = CheckpointState.decode(json)
        val restored = IncrementalTranscript(state.recognition.chunks) { _, _ -> error("A restore must not decode") }
        assertEquals(state.positionSamples, restored.positionSamples)
        assertEquals(state.doneMs, restored.doneMs)
        val again = JSONObject(CheckpointState.json(restored.checkpoint()).toString())
        val chunks = json.getJSONObject("recognition").getJSONObject("chunks")
        for (key in listOf("start", "end", "window")) {
            assertEquals(key, chunks.get(key).toString(), again.get(key).toString())
        }
        fun items(chunks: JSONObject) = chunks.getJSONObject("words").getJSONArray("items").toString()
        assertEquals(items(chunks), items(again))
    }

    @Test fun aValidCheckpointPassesTheResumeRules() {
        val job = Job()
        CheckpointState.validate(stored(job.state), job.expected())
        CheckpointState.validate(job.state, job.expected())
    }

    @Test fun eachResumeRuleRejects() {
        val job = Job()
        fun rejects(expected: CheckpointState.Expected = job.expected(), change: (JSONObject) -> Unit = {}) {
            val json = stored(job.state).also(change)
            val state = CheckpointState.decode(json)
            assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(json, expected) }
            assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(state, expected) }
        }
        rejects { it.put("version", 3) }
        rejects { it.put("version", 0) }
        fun rejectsLog(json: JSONObject, expected: CheckpointState.Expected) =
            assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(json, expected) }
        // Version 1 has no word log.
        val log = WordLogRef(1, 10).json()
        rejectsLog(stored(job.state).put("version", 1).put("wordLog", log), job.expected(wordLogBytes = 10))
        // The log is shorter than the prefix the checkpoint names.
        rejectsLog(stored(job.state).put("wordLog", log), job.expected(wordLogBytes = 9))
        rejects { it.put("source", "file:///elsewhere.m4a") }
        rejects(job.expected(signature = "1:2"))
        rejects { it.put("model", "another revision") }
        rejects { it.put("speakers", "another sha") }
        rejects { it.put("clipSamples", CheckpointState.MAX_CLIP_SAMPLES + 1) }
        rejects { it.put("clipSamples", -1) }
        rejects { it.put("clipSamples", 16_001) }
        rejects { it.put("positionSamples", -1) }
        rejects { it.put("positionSamples", Limits.MAX_SAMPLES + 1L) }
        rejects { it.put("doneMs", Limits.MAX_TRANSCRIBE_MS + 1) }
        rejects { it.getJSONObject("encoder").put("length", job.audio.length() + 1) }
        // Version 1, as 0.1.0 wrote it, still resumes.
        CheckpointState.validate(stored(job.state).put("version", 1).apply { remove("wordLog") }, job.expected())
        // A log at least as long as the prefix passes.
        CheckpointState.validate(stored(job.state).put("wordLog", log), job.expected(wordLogBytes = 10))
        // A job that keeps the source's own Opus audio has no encoder and does not check the audio part.
        CheckpointState.validate(stored(job.state).apply { remove("encoder") }, job.expected())
    }

    @Test fun aChangedSourceInvalidatesTheCheckpoint() {
        val job = Job()
        val json = stored(job.state)
        assertTrue(job.source.setLastModified(job.source.lastModified() + 1000))
        assertThrows(IllegalArgumentException::class.java) { CheckpointState.validate(json, job.expected()) }
    }

    @Test fun theSignatureIsLengthAndModificationTime() {
        val job = Job()
        assertEquals("4096:${job.source.lastModified()}", CheckpointState.signature(job.source))
        assertEquals("null:null", CheckpointState.signature(null))
    }

    @Test fun theDirectoryKeyIsTheSha256OfTheStoredSourceString() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", CheckpointState.key(""))
        assertEquals(64, CheckpointState.key("file:///data/user/0/org.cassini.android/files/documents/a.m4a").length)
    }

    @Test fun loggedWordsLeaveTheCheckpointAndComeBackFirst() {
        val state = Job().state
        val json = JSONObject(CheckpointState.encode(state, WordLogRef(1, 40)).toString())
        val inline = json.getJSONObject("recognition").getJSONObject("chunks").getJSONObject("words")
        assertEquals(1, inline.getJSONArray("items").length())
        assertEquals(WordLogRef(1, 40), CheckpointState.wordLog(json))
        assertEquals(2, CheckpointState.wordCount(json))
        assertEquals(words, CheckpointState.decode(json, words.subList(0, 1)).recognition.chunks.words)
        // The log must hold exactly the prefix the checkpoint names.
        assertThrows(IllegalArgumentException::class.java) { CheckpointState.decode(json) }
        assertThrows(IllegalArgumentException::class.java) { CheckpointState.encode(state, WordLogRef(3, 99)) }
    }

    @Test fun namesRoundTripWithExactlyTheirMembers() {
        val labels = mapOf("${prefix}1" to "Ada Example")
        val names = CheckpointNames(labels, emptyMap(), mapOf("${prefix}1" to ""), emptyMap(), 4)
        val json = JSONObject(CheckpointNames.encode(names).toString())
        assertEquals(CheckpointNames.KEYS, json.keys().asSequence().toSet())
        assertEquals(names, CheckpointNames.decode(json))
    }

    @Test fun namesWrittenBeforeTheExtrasExistedRead() {
        val json = JSONObject().put("labels", JSONObject().put("a", "Ada")).put("merges", JSONObject())
        assertEquals(CheckpointNames(mapOf("a" to "Ada"), emptyMap()), CheckpointNames.decode(json))
    }

    @Test fun oversizedArraysAreRefused() {
        val tooMany = CheckpointData.floats(FloatArray(SpeakerBudget.DIARIZER_STATE_FLOATS + 1))
        val cache = JSONObject().put("cache", tooMany)
            .put("elapsedMs", 0).put("turns", JSONArray())
        assertThrows(IllegalArgumentException::class.java) { CheckpointState.diarizer(cache) }
        val samples = FloatArray(IncrementalTranscript.WINDOW_SAMPLES + 1)
        val window = CheckpointState.json(IncrementalState(0, 0, samples, emptyList()))
        assertThrows(IllegalArgumentException::class.java) { CheckpointState.incremental(window) }
    }
}
