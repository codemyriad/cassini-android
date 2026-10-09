package org.cassini.android

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
        val restored = IncrementalTranscript(chunks) { _, _ -> error("A restore must not decode") }
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
