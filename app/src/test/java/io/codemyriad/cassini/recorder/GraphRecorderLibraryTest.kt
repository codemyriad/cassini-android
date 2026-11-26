package io.codemyriad.cassini.recorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.OggOpus
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.db.NoteCache
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/** The library port of the recorder sync over a real graph: import, the consent wait, and no transcript yet. */
@RunWith(RobolectricTestRunner::class)
class GraphRecorderLibraryTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var graph: AppGraph

    @After fun tearDown() = graph.close()

    @Test fun aDownloadedWavBecomesANoteThatWaitsForModelConsent() = runBlocking {
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        graph = AppGraph(
            context,
            AppGraph.Config(
                paths = paths,
                dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
                fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
                network = { false },
            ),
        )
        val library = GraphRecorderLibrary(graph) { "Speaker $it" }
        val wav = File(folder.newFolder(), "REC_0001.wav.part").apply { writeBytes(wav(16_000)) }

        val created = withTimeout(TIMEOUT_MS) { library.import(wav, "REC_0001.wav") }
        assertTrue(created is LibraryImport.Created)
        val noteId = (created as LibraryImport.Created).noteId
        val notes = graph.awaitCatalogue().notes
        assertEquals("REC_0001", notes.note(noteId).first()!!.note.title)
        assertEquals(LibraryImport.Existing(noteId), library.import(wav, "REC_0001.wav"))

        // No transcript and no job yet: a sync that found the note again asks for the transcription.
        assertTrue(library.needsTranscription(noteId))
        library.transcribe(noteId)
        assertEquals(JobState.NEEDS_CONSENT, graph.awaitCatalogue().jobs.job(noteId)?.state)
        // A job exists now: no second request.
        assertFalse(library.needsTranscription(noteId))
        assertFalse(library.needsTranscription("missing"))
        assertEquals(emptyMap<String, RecorderTranscript>(), library.transcripts(listOf(noteId, "missing")))
    }

    @Test fun transcriptsRefreshDroppedWordCacheAndFallBackToAReadableVariant() = runBlocking {
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        graph = AppGraph(
            context,
            AppGraph.Config(
                paths = paths,
                dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
                fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
                network = { false },
            ),
        )
        val library = GraphRecorderLibrary(graph) { "Speaker $it" }
        val opus = sealedDocument("Standup", listOf("Hello", "world"))
        val imported = withTimeout(TIMEOUT_MS) { library.import(opus, "Standup.opus") } as LibraryImport.Created
        val noteId = imported.noteId

        // Evicting the cached word rows must re-derive them from document.opus instead of producing an empty CWX.
        val cache = graph.awaitCatalogue().database.cache()
        cache.dropWords(noteId)
        val refreshed = library.transcripts(listOf(noteId)).getValue(noteId)
        assertEquals(listOf("Hello", "world"), refreshed.cwx.words.map { it.text })

        // If selectedVariant names an unreadable variant (and no variant is marked default), fall back to the first
        // readable variant with words, matching NoteRepository.selectedVariant.
        val details = graph.awaitCatalogue().notes.note(noteId).first()!!
        val good = details.variants.single().copy(isDefault = false, ordinal = 1)
        val unreadable = good.copy(variantId = "broken", ordinal = 0, readable = false, isDefault = false)
        val words = cache.words(noteId, good.variantId)
        cache.replaceCache(
            NoteCache(
                note = details.note.copy(selectedVariant = "broken"),
                variants = listOf(unreadable, good),
                speakers = details.speakers,
                words = words,
                body = "Hello world",
            ),
        )
        val fallback = library.transcripts(listOf(noteId)).getValue(noteId)
        assertEquals(good.variantId, fallback.variantId)
        assertEquals(listOf("Hello", "world"), fallback.cwx.words.map { it.text })
    }

    @Test fun aTranscriptWithoutWordsIsNoSpeechAndNeverGoesToTheRecorder() = runBlocking {
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        graph = AppGraph(
            context,
            AppGraph.Config(
                paths = paths,
                dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
                fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
                network = { false },
            ),
        )
        val library = GraphRecorderLibrary(graph) { "Speaker $it" }
        // A silent recording whose transcription finished with no words.
        val opus = sealedDocument("REC_0031", emptyList())
        val noteId = (withTimeout(TIMEOUT_MS) { library.import(opus, "REC_0031.opus") } as LibraryImport.Created).noteId

        // Nothing to send: an empty transcript never becomes a sidecar.
        assertEquals(emptyMap<String, RecorderTranscript>(), library.transcripts(listOf(noteId)))
        assertFalse(library.needsTranscription(noteId))
        // The Library row reads as transcribed without speech, not as waiting: no push starts for it.
        val row = graph.awaitCatalogue().notes.library().first().single { it.id == noteId }
        val info = RecorderNotes.info(row)
        assertEquals(NoteSyncInfo("REC_0031", null, transcribed = false, noSpeech = true), info)
        assertEquals(emptySet<String>(), RecorderNotes.pushable(mapOf(noteId to info)))
    }

    private fun sealedDocument(title: String, words: List<String>): File {
        val dir = folder.newFolder()
        val raw = File(dir, "raw.opus")
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0x38, 1, -128, -69, 0, 0, 0, 0, 0)
        val tags = CassiniDocument.tagsPacket(listOf("ENCODER=test"))
        val packets = (0 until 8).map { idx -> byteArrayOf(0xF8.toByte(), idx.toByte(), 1) }
        raw.writeBytes(OggOpus.mux(OggOpus.Stream(head, tags, packets, 8 * 960L, true), tags, 0xCA55))
        val out = File(dir, "sealed.opus")
        val transcript = Transcript(words.mapIndexed { idx, w -> Word("S1", idx * 400L, idx * 400L + 300L, w) })
        CassiniDocument.create(
            raw,
            transcript,
            title,
            JSONObject().put("engine", "test"),
            null,
            mapOf("S1" to "Ada"),
            out,
            createdAtUtc = Instant.parse("2026-11-25T10:00:00Z"),
        )
        return out
    }

    /** A 16 kHz mono 16-bit PCM WAV of [samples] samples of a quiet tone. */
    private fun wav(samples: Int): ByteArray {
        val data = samples * 2
        val buffer = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(data)
        repeat(samples) { buffer.putShort(((it % 32) * 100).toShort()) }
        return buffer.array()
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}
