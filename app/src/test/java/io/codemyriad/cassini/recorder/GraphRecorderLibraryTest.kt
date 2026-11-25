package io.codemyriad.cassini.recorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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

        library.transcribe(noteId)
        assertEquals(JobState.NEEDS_CONSENT, graph.awaitCatalogue().jobs.job(noteId)?.state)
        assertEquals(emptyMap<String, RecorderTranscript>(), library.transcripts(listOf(noteId, "missing")))
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
