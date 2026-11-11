package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.data.checkpoint.CheckpointRepository
import io.codemyriad.cassini.data.db.offMain
import io.codemyriad.cassini.data.voices.VoiceRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

/** Fixture generator executed only against unmodified v1 production code at d65fd02b. */
@RunWith(RobolectricTestRunner::class)
class GenerateV1TimeFixtureTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun generate() = runBlocking {
        val output = File(checkNotNull(System.getenv("CASSINI_V1_TIME_FIXTURE_OUT"))).apply { mkdirs() }
        val h = NotesHarness(folder)
        val start = Instant.now().minusSeconds(540).toEpochMilli()
        h.now = start
        val id = h.notes.createRecording("Existing own meeting", start, "opus")
        val audio = File(h.dir(id), "capture.opus")
        fixtureOpus.copyTo(audio)
        h.notes.finishRecording(id, OggOpus.scan(audio).durationMs)
        h.now = start + 540_000
        offMain {
            h.documents.sealBlocking(id, audio, transcript("We kept the original recording time."), "Existing own meeting", JSONObject().put("engine", "fixture"), null, emptyMap())
        }
        h.notes.adoptSealed(id)
        assertEquals(start, h.row(id).createdAt)
        val document = h.readFile(id)
        assertFalse(document.manifest!!.getJSONObject("meeting").has("recordedAtUtc"))
        val sealed = document.manifest!!.getJSONObject("meeting").getString("createdAtUtc")
        h.document(id).copyTo(File(output, "document.opus"), overwrite = true)
        h.close()
        h.paths.database.copyTo(File(output, "own-v1.db"), overwrite = true)

        val secondFolder = TemporaryFolder().apply { create() }
        try {
            val rebuilt = NotesHarness(secondFolder)
            rebuilt.now = Instant.parse(sealed).plusSeconds(60).toEpochMilli()
            rebuilt.dir(id).mkdirs()
            File(output, "document.opus").copyTo(rebuilt.document(id))
            val voices = VoiceRepository(rebuilt.db, rebuilt.paths, rebuilt.dispatchers,
                CheckpointRepository(rebuilt.db, rebuilt.paths, rebuilt.dispatchers, rebuilt.locks).names)
            CatalogueRepository(rebuilt.db, rebuilt.paths, rebuilt.dispatchers, rebuilt.locks, rebuilt.documents, voices,
                clock = { rebuilt.now }).rebuild()
            assertEquals(Instant.parse(sealed).toEpochMilli(), rebuilt.row(id).createdAt)
            rebuilt.close()
            rebuilt.paths.database.copyTo(File(output, "rebuilt-v1.db"), overwrite = true)
        } finally { secondFolder.delete() }
        File(output, "expected.json").writeText(JSONObject().put("id", id).put("startUtc", Instant.ofEpochMilli(start).toString())
            .put("sealedUtc", sealed).put("producerCommit", "d65fd02b").toString(2) + "\n")
    }
}
