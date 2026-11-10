package io.codemyriad.cassini.models

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.DocumentState
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.jobs.EnqueueRefusal
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.data.notes.ImportOutcome
import io.codemyriad.cassini.processing.ServiceStarter
import io.codemyriad.cassini.processing.TranscribeOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26]) // No Included recognizer, regardless of the build host's ABI.
class GraphModelDownloadPortsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun unreadyParakeetIsShownAndExplicitlyConsentedBeforeTheServiceStarts() = runBlocking {
        withNote {
            val plan = ports.plan()
            assertEquals(listOf(ModelPart.SPEECH, ModelPart.SPEAKERS), plan.parts)
            val speechBytes = ModelPart.SPEECH.models.sumOf { it.downloadBytes }
            assertEquals(listOf(speechBytes, ModelCatalog.speakerBytes), plan.missing.map { it.downloadBytes })
            assertFalse(graph.models.speechConsented())
            ports.recordConsent(setOf(ModelPart.SPEAKERS))
            assertFalse(graph.models.speechConsented())
            assertEquals(TranscribeOutcome.NeedsConsent, ports.transcribe(noteId))
            assertEquals(JobState.NEEDS_CONSENT, graph.awaitCatalogue().jobs.job(noteId)?.state)
            assertEquals(0, serviceStarts.get())

            ports.recordConsent(plan.parts.toSet())
            assertTrue(graph.models.consented(ModelPart.SPEECH))
            assertTrue(graph.models.consented(ModelPart.SPEAKERS))
            assertFalse(graph.models.consented(ModelPart.VOICES))
            assertQueued()
        }
    }

    @Test fun readyParakeetNeedsOnlySpeakerConsentAndAnUnverifiedNoteCannotQueue() = runBlocking {
        withNote(parakeetReady = true) {
            val plan = ports.plan()
            assertEquals(listOf(ModelPart.SPEAKERS), plan.parts)
            assertEquals(ModelCatalog.speakerBytes, plan.totalBytes)
            assertFalse(graph.models.consented(ModelPart.SPEAKERS))
            ports.recordConsent(plan.parts.toSet())
            assertTrue(graph.models.consented(ModelPart.SPEAKERS))
            assertEquals(null, graph.settings.speechConsentAt.first())
            assertFalse(graph.models.consented(ModelPart.VOICES))
            assertQueued()

            val catalogue = graph.awaitCatalogue()
            val job = catalogue.jobs.job(noteId)
            val row = requireNotNull(catalogue.database.notes().note(noteId))
            catalogue.database.notes().update(row.copy(documentState = DocumentState.STALE_AUDIO))
            assertEquals(TranscribeOutcome.Refused(EnqueueRefusal.DOCUMENT_NOT_VERIFIED), ports.transcribe(noteId))
            assertEquals(job, catalogue.jobs.job(noteId))
            assertEquals(1, serviceStarts.get())
        }
    }

    private data class NoteFixture(
        val graph: AppGraph,
        val ports: GraphModelDownloadPorts,
        val noteId: String,
        val serviceStarts: AtomicInteger,
    ) {
        suspend fun assertQueued() {
            assertEquals(TranscribeOutcome.Queued(changed = true), ports.transcribe(noteId))
            val job = requireNotNull(graph.awaitCatalogue().jobs.job(noteId))
            assertEquals(noteId, job.noteId)
            assertEquals(JobKind.TRANSCRIBE_AGAIN, job.kind)
            assertEquals(JobState.QUEUED, job.state)
            assertEquals(SpeechModel.PARAKEET.identity, job.speechIdentity)
            assertEquals(1, serviceStarts.get())
        }
    }

    private suspend fun withNote(parakeetReady: Boolean = false, checkNote: suspend NoteFixture.() -> Unit) {
        val context: Context = ApplicationProvider.getApplicationContext()
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        val serviceStarts = AtomicInteger()
        if (parakeetReady) {
            val model = ModelCatalog.PARAKEET
            val directory = model.directory(paths.modelRoot)
            check(directory.mkdirs())
            model.artifacts.forEach { artifact ->
                RandomAccessFile(File(directory, artifact.name), "rw").use { it.setLength(artifact.size) }
            }
            File(directory, model.markerFile).writeText(model.markerValue)
        }
        val graph = AppGraph(
            context,
            AppGraph.Config(
                paths,
                dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
                fetcher = ModelFetcher { _, _, _ -> error("Opening or consenting never fetches a model") },
                processing = AppGraph.ProcessingHooks(starter = {
                    ServiceStarter {
                        serviceStarts.incrementAndGet()
                        true
                    }
                }),
            ),
        )
        try {
            withTimeout(10_000) { graph.ready.await() }
            val catalogue = graph.awaitCatalogue()
            val clip = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.opus")
            val document = File(folder.root, "team-update.opus")
            CassiniDocument.create(
                clip,
                Transcript(listOf(Word("a", 0, 500, "Hello"))),
                "Team update",
                JSONObject().put("source", "test"),
                null,
                emptyMap(),
                document,
            )
            val imported = catalogue.imports.import(Uri.fromFile(document))
            assertTrue("the source is a verified document", imported is ImportOutcome.Created)
            val id = (imported as ImportOutcome.Created).noteId
            assertEquals(DocumentState.OK, catalogue.database.notes().note(id)?.documentState)
            assertEquals(null, catalogue.jobs.job(id))
            val ports = GraphModelDownloadPorts(graph, context, id, part = ModelPart.SPEAKERS)
            NoteFixture(graph, ports, id, serviceStarts).checkNote()
        } finally {
            graph.close()
        }
    }
}
