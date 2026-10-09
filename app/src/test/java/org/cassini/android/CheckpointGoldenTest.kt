package org.cassini.android

import org.cassini.core.asr.IncrementalTranscript
import org.cassini.core.audio.PcmFile
import org.cassini.core.checkpoint.CheckpointData
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.OggOpus
import org.cassini.core.format.Transcript
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The checkpoint a 0.1.0 job left on a device (fixtures/filesdir/v0.1.0-basic, captured by FixtureCaptureTest)
 * read through ProcessingCheckpoint and the classes that restore from it. A typed codec must pass the same checks.
 * Equality is semantic: the JVM org.json orders members differently from Android's.
 */
@RunWith(RobolectricTestRunner::class)
class CheckpointGoldenTest {
    @get:Rule val folder = TemporaryFolder()

    private fun tree() = FixtureTree.materialise("v0.1.0-basic", folder.newFolder("files"))

    private fun captured(): Pair<FixtureTree, ProcessingCheckpoint> {
        val tree = tree()
        val (source, directory) = tree.checkpoints.entries.single()
        val checkpoint = ProcessingCheckpoint(tree.root, source)
        assertEquals(directory, checkpoint.directory)
        return tree to checkpoint
    }

    @Test fun committedDirectoryIsKeyedByTheStoredSourceString() {
        val committed = File(FixtureTree.directory(), "v0.1.0-basic/processing-checkpoints").listFiles()!!.single()
        val stored = JSONObject(File(committed, "checkpoint.json").readText()).getString("source")
        assertTrue(stored.startsWith("file://${FixtureTree.DEVICE_ROOT}/documents/"))
        assertEquals(FixtureTree.key(stored), committed.name)
    }

    @Test fun capturedCheckpointHasExactlyTheVersionOneMembers() {
        val saved = captured().second.load()
        assertNotNull("The source signature, model and clip lengths must validate", saved)
        assertEquals(
            setOf(
                "version", "source", "signature", "model", "speakers", "recognition", "encoder", "diarizer", "speakerPrefix",
                "speakerFailure", "clipSamples", "voiceClips", "positionSamples", "doneMs", "durationMs", "elapsedMs",
            ),
            saved!!.keys().asSequence().toSet(),
        )
        assertEquals(1, saved.getInt("version"))
        assertEquals(setOf("inferenceMs", "chunks"), saved.getJSONObject("recognition").keys().asSequence().toSet())
        assertEquals(
            setOf("start", "end", "window", "words"),
            saved.getJSONObject("recognition").getJSONObject("chunks").keys().asSequence().toSet()
        )
        assertEquals(
            setOf("history", "frame", "samples", "pending", "length", "serial", "sequence", "granule", "packet"),
            saved.getJSONObject("encoder").keys().asSequence().toSet(),
        )
        assertEquals(setOf("cache", "elapsedMs", "turns"), saved.getJSONObject("diarizer").keys().asSequence().toSet())
        assertTrue(
            "Stopped after the first window",
            saved.getLong("doneMs") in 28_000 until saved.getLong("durationMs")
        )
    }

    @Test fun recognitionRestoresWhereTheJobStopped() {
        val saved = captured().second.load()!!
        val chunks = saved.getJSONObject("recognition").getJSONObject("chunks")
        val state = CheckpointState.incremental(chunks)
        val restored = IncrementalTranscript(state) { _, _ -> error("A restore must not decode") }
        assertEquals(saved.getLong("positionSamples"), restored.positionSamples)
        assertEquals(saved.getLong("doneMs"), restored.doneMs)
        val words = Transcript.fromJson(chunks.getJSONObject("words").toString()).words
        assertTrue(words.isNotEmpty())
        assertTrue(words.any { it.text.contains("settentrionale", ignoreCase = true) })
        assertTrue(
            words.all { it.speaker.startsWith(saved.getString("speakerPrefix")) && it.endMs <= saved.getLong("doneMs") }
        )
    }

    @Test fun encoderDiarizerAndClipStateDecode() {
        val (_, checkpoint) = captured()
        val saved = checkpoint.load()!!
        val encoder = saved.getJSONObject("encoder")
        assertEquals(640, CheckpointData.floats(encoder.getString("history"), 640).size)
        assertEquals(320, CheckpointData.floats(encoder.getString("frame"), 320).size)
        assertEquals(saved.getLong("positionSamples"), encoder.getLong("samples"))
        assertTrue(encoder.getInt("pending") in 0..319)
        assertTrue(encoder.getLong("length") <= checkpoint.audio.length())
        checkpoint.audio.inputStream().use { OggOpus.headers(it) }
        val diarizer = saved.getJSONObject("diarizer")
        assertTrue(CheckpointData.floats(diarizer.getString("cache"), 200_000).isNotEmpty())
        val turns = diarizer.getJSONArray("turns")
        assertTrue(turns.length() > 0)
        for (i in 0 until turns.length()) {
            val turn = turns.getJSONArray(i)
            assertTrue(turn.getLong(0) < turn.getLong(1) && turn.getInt(2) in 0..7)
        }
        PcmFile(checkpoint.clips, deleteOnClose = false).use { clips ->
            assertEquals(saved.getInt("clipSamples"), clips.sampleCount)
            val cache = VoiceClipCache(clips, saved.getJSONObject("voiceClips"))
            assertEquals(setOf(saved.getString("speakerPrefix") + "1"), cache.windows.keys)
        }
    }

    @Test fun draftNamesAndTheirGenerationLoad() {
        val (_, checkpoint) = captured()
        val prefix = checkpoint.load()!!.getString("speakerPrefix")
        assertEquals(mapOf("${prefix}1" to "Ada Example") to emptyMap<String, String>(), checkpoint.loadNames())
        assertEquals(mapOf("${prefix}1" to "") to emptyMap<String, String>(), checkpoint.nameExtras())
        assertEquals(4L, checkpoint.rememberGeneration())
    }

    @Test fun thePausedNoteNamesTheCheckpointSource() {
        val (tree, checkpoint) = captured()
        val source = tree.checkpoints.keys.single()
        val note = LibraryStore(tree.root).load().single { it.session.uri == source }
        assertTrue(note.session.processingPaused)
        assertEquals(null, note.session.document)
        assertTrue(note.session.transcript!!.words.isNotEmpty())
        assertNotNull(checkpoint.load())
    }

    @Test fun savingTheLoadedStateWritesTheSameCheckpoint() {
        val (_, checkpoint) = captured()
        val saved = checkpoint.load()!!
        checkpoint.save(JSONObject(saved.toString()))
        assertEquals(plain(saved), plain(checkpoint.load()!!))
    }

    @Test fun aChangedSourceInvalidatesTheCheckpoint() {
        val (tree, checkpoint) = captured()
        val source = File(android.net.Uri.parse(tree.checkpoints.keys.single()).path!!)
        assertTrue(source.setLastModified(source.lastModified() + 1000))
        assertThrows(IllegalArgumentException::class.java) { checkpoint.load() }
    }

    /**
     * The fixture comes from 0ebea43, which omits an empty language; the v0.1.0 tag that users installed wrote
     * `"language":"it"` into every word body (fixtures/README.md). Put it back in the checkpoint, the library's inline
     * transcripts and latest.words.json, as the tag wrote it, and read them the same way. latest.processing.json is
     * left alone: 0.1.0 only writes it, nothing reads it. [theReleasedTagsSealedDocumentReads] covers note 2's
     * sealed document.
     */
    @Test fun theReleasedTagsLanguageMemberReads() {
        val (tree, checkpoint) = captured()
        fun edit(file: File, change: (JSONObject) -> Unit) {
            val json = JSONObject(file.readText())
            change(json)
            file.writeText(json.toString())
        }
        edit(File(checkpoint.directory, "checkpoint.json")) {
            it.getJSONObject("recognition").getJSONObject("chunks").getJSONObject("words").put("language", "it")
        }
        edit(File(tree.root, "library.json")) { library ->
            val notes = library.getJSONArray("notes")
            for (i in 0 until notes.length()) {
                notes.getJSONObject(i).getJSONObject("session").optJSONObject("transcript")?.put("language", "it")
            }
        }
        edit(File(tree.root, "latest.words.json")) { it.put("language", "it") }

        val saved = checkpoint.load()!!
        val chunks = saved.getJSONObject("recognition").getJSONObject("chunks")
        assertEquals("it", Transcript.fromJson(chunks.getJSONObject("words").toString()).language)
        val state = CheckpointState.incremental(chunks)
        val restored = IncrementalTranscript(state) { _, _ -> error("A restore must not decode") }
        assertEquals(saved.getLong("positionSamples"), restored.positionSamples)
        assertEquals(saved.getLong("doneMs"), restored.doneMs)
        val transcripts = LibraryStore(tree.root).load().mapNotNull { it.session.transcript }
        assertTrue(transcripts.isNotEmpty())
        assertTrue(transcripts.all { it.language == "it" && it.words.isNotEmpty() })
        val latest = Transcript.fromJson(File(tree.root, "latest.words.json").readText())
        assertEquals("it", latest.language)
        assertTrue(latest.words.isNotEmpty())
    }

    /**
     * Note 2's sealed document as the v0.1.0 tag wrote it: the same audio and words, sealed by the unchanged
     * CassiniDocument writer with the tag's inputs (a transcript whose language is "it" and a recognition record
     * with `"language":"it"`), in place of the 0ebea43 document. The library must still open it.
     */
    @Test fun theReleasedTagsSealedDocumentReads() {
        val tree = tree()
        val path = LibraryStore(tree.root).load().mapNotNull { it.session.document }.single()
        val file = File(path)
        val captured = CassiniDocument.read(file)
        assertEquals("ok", captured.state)
        val variant = captured.selected(null)!!
        val words = variant.transcript!!
        val digest = captured.manifest!!.getJSONObject("integrity").getString("opusAudioSha256")
        val plain = File(folder.root, "plain.opus")
        val untagged = captured.comments.filterNot { it.startsWith("CASSINI_", ignoreCase = true) }
        OggOpus.rewrite(file, CassiniDocument.tagsPacket(untagged), plain)
        val speechToText = captured.manifest!!.getJSONObject("provenance").getJSONObject("speechToText")
        val recognition = speechToText.getJSONObject(variant.id)
        val processing = JSONObject(recognition.toString()).put("language", "it")
        val labels = words.words.map { it.speaker }.distinct().associateWith { captured.speakerLabel(it)!! }
        CassiniDocument.create(plain, words.copy(language = "it"), captured.title, processing, null, labels, file)

        val note = LibraryStore(tree.root).load().single { it.session.document == path }
        val sealed = CassiniDocument.read(File(note.session.document!!))
        assertEquals("ok", sealed.state)
        val default = sealed.selected(note.session.selectedVariant)!!
        assertEquals("it", default.descriptor.getString("language"))
        assertEquals("it", default.transcript!!.language)
        assertEquals("it", JSONObject(default.body!!).getString("language"))
        assertEquals(words.words, default.transcript!!.words)
        val provenance = sealed.manifest!!.getJSONObject("provenance").getJSONObject("speechToText")
        assertEquals("it", provenance.getJSONObject(default.id).getString("language"))
        assertEquals(digest, sealed.manifest!!.getJSONObject("integrity").getString("opusAudioSha256"))
        assertEquals(captured.title, sealed.title)
        labels.forEach { (id, label) -> assertEquals(label, sealed.speakerLabel(id)) }
    }

    @Test fun manifestCoversEveryCommittedFile() {
        FixtureTree.directory().listFiles()!!.filter { it.isDirectory && File(it, ".mtimes").exists() }.forEach { tree ->
            val committed = tree.walkTopDown().filter { it.isFile && it.name != ".mtimes" }
            val files = committed.map { it.relativeTo(tree).invariantSeparatorsPath }.toSet()
            assertEquals(tree.name, files, FixtureTree.manifest(tree).keys)
        }
        assertArrayEquals(arrayOf(".keep"), File(FixtureTree.directory(), "empty").list())
    }

    /** JSON as nested maps and lists, so member order does not matter. */
    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { plain(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        else -> value
    }
}
