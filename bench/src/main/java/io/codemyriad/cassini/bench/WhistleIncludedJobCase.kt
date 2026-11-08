package io.codemyriad.cassini.bench

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.adapters.NetworkCheck
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.PauseReason
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.db.NoteEntity
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.engine.job.JobOutcome
import io.codemyriad.cassini.engine.job.JobEvent
import io.codemyriad.cassini.processing.TranscriptionJobFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/** Real offline production job over the unchanged public English DC/control fixtures, in a fresh scratch graph. */
@RequiresApi(Build.VERSION_CODES.Q)
internal object WhistleIncludedJobCase {
    fun run(name: String, expected: List<JSONObject>) = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "included-job-$name").apply { mkdirs() }
        val paths = AppPaths(File(root, UUID.randomUUID().toString()), File(root, "cache").apply { mkdirs() })
        val downloads = AtomicInteger()
        val graph = AppGraph(
            context,
            AppGraph.Config(
                paths,
                fetcher = ModelFetcher { _, _, _ ->
                    downloads.incrementAndGet()
                    error("Included must not download any model")
                },
                network = NetworkCheck { false },
            ),
        )
        try {
            graph.ready.await()
            assertTrue(graph.speech.included.await())
            assertNull(graph.settings.speechModel.first())
            assertEquals(SpeechModel.INCLUDED, graph.speech.selected())
            assertNull(graph.settings.consentAt.first())
            val catalogue = graph.awaitCatalogue()
            val id = UUID.randomUUID().toString()
            val directory = paths.note(id).apply { mkdirs() }
            instrumentation.context.assets.open("whistle-probe/$name.wav").use { input ->
                File(directory, "source.wav").outputStream().use { output -> input.copyTo(output) }
            }
            catalogue.database.notes().insert(
                NoteEntity(
                    id = id,
                    title = "English sample",
                    createdAt = 1,
                    addedAt = 1,
                    origin = Origin.IMPORTED_AUDIO,
                    audioFile = "source.wav",
                    audioKind = AudioKind.PLAIN,
                    updatedAt = 1,
                ),
            )
            catalogue.jobs.enqueue(id, JobKind.TRANSCRIBE)
            assertEquals(SpeechModel.INCLUDED.identity, catalogue.jobs.job(id)?.speechIdentity)
            catalogue.jobs.claimNext()
            val job = TranscriptionJobFactory(graph).create(id)
            val watcher = launch {
                for (event in job.events) {
                    if (event is JobEvent.Checkpointed) job.stop(PauseReason.USER)
                }
            }
            val paused = job.run()
            watcher.join()
            assertEquals(JobOutcome.Paused(PauseReason.USER), paused)
            val saved = requireNotNull(catalogue.checkpoints.load(id) as? io.codemyriad.cassini.data.checkpoint.CheckpointLoad.Valid).state
            catalogue.jobs.markPaused(id, PauseReason.USER, saved.doneMs, saved.durationMs, saved.recognition.chunks.words.size)
            graph.speech.choose(SpeechModel.PARAKEET)
            assertTrue(graph.transcriptionConsented(id))
            catalogue.jobs.enqueue(id, JobKind.TRANSCRIBE)
            assertEquals(SpeechModel.INCLUDED.identity, catalogue.jobs.job(id)?.speechIdentity)
            catalogue.jobs.claimNext()
            val resumed = TranscriptionJobFactory(graph).create(id)
            val outcome = resumed.run()
            val document = CassiniDocument.read(File(directory, "document.opus"))
            val variant = document.selected(null)
            val transcript = variant?.transcript
            val report = JSONObject()
                .put("case", name).put("sdk", Build.VERSION.SDK_INT).put("outcome", outcome.toString())
                .put("documentState", document.state).put("downloads", downloads.get())
                .put("resumedFromMs", saved.doneMs).put("savedPosition", saved.positionSamples)
                .put("transcript", transcript?.let { JSONObject(it.json()) })
            File(context.filesDir, "whistle-included-job-$name.json").writeText(report.toString(2))
            assertEquals(JobOutcome.Done, outcome)
            assertEquals("ok", document.state)
            assertEquals(0, downloads.get())
            assertNull(graph.settings.consentAt.first())
            assertFalse(graph.models.ready(ModelPart.SPEECH))
            val words = requireNotNull(transcript).words
            val placed = expected.flatMap { segment ->
                val offset = segment.getLong("startSample").toDouble() / 16000
                val items = segment.getJSONObject("macResult").getJSONArray("words")
                (0 until items.length()).map { items.getJSONObject(it) to offset }
            }
            assertEquals(placed.map { it.first.getString("word").trim() }, words.map { it.text })
            for (i in words.indices) {
                val (word, offset) = placed[i]
                assertTrue(abs(words[i].startMs / 1000.0 - offset - word.getDouble("start")) <= 0.080000000001)
                assertTrue(abs(words[i].endMs / 1000.0 - offset - word.getDouble("end")) <= 0.080000000001)
            }
            val provenance = requireNotNull(document.manifest).getJSONObject("provenance")
                .getJSONObject("speechToText").getJSONObject(requireNotNull(variant).id)
            assertEquals("needle", provenance.getString("backend"))
            assertEquals("Whistle", provenance.getString("engine"))
            assertEquals(saved.doneMs, provenance.getLong("x-resumedFromMs"))
            report.put("provenance", provenance).put("words", JSONArray(words.map { it.text }))
            File(context.filesDir, "whistle-included-job-$name.json").writeText(report.toString(2))
        } finally {
            graph.close()
            paths.root.deleteRecursively()
        }
    }
}
