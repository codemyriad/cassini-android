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
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.jobs.EnqueueRefusal
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.data.notes.ImportOutcome
import io.codemyriad.cassini.processing.ServiceStarter
import io.codemyriad.cassini.processing.TranscribeOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class GraphModelDownloadPortsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun speakerConsentAutomaticallyRequestsTheSameVerifiedNoteWithoutOtherModelPermissions() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        val graph = AppGraph(
            context,
            AppGraph.Config(
                paths,
                dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
                fetcher = ModelFetcher { _, _, _ -> error("Opening or consenting never fetches a model") },
                processing = AppGraph.ProcessingHooks(starter = { ServiceStarter { false } }),
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
            val ports = GraphModelDownloadPorts(graph, context, id, part = ModelPart.SPEAKERS)
            val plan = ports.plan()
            assertEquals(listOf(ModelPart.SPEAKERS), plan.parts)
            assertEquals(ModelCatalog.speakerBytes, plan.totalBytes)
            assertFalse(graph.models.consented(ModelPart.SPEAKERS))
            assertFalse(graph.models.consented(ModelPart.VOICES))
            assertFalse(graph.models.speechConsented())
            assertEquals(null, catalogue.jobs.job(id))
            ports.recordConsent(plan.parts.toSet())
            assertTrue(graph.models.consented(ModelPart.SPEAKERS))
            assertFalse(graph.models.speechConsented())
            assertFalse(graph.models.consented(ModelPart.VOICES))
            ports.transcribe(id)
            val job = catalogue.jobs.job(id)
            assertNotNull(job)
            assertEquals(id, job?.noteId)
            assertEquals(JobKind.TRANSCRIBE_AGAIN, job?.kind)
            val row = requireNotNull(catalogue.database.notes().note(id))
            catalogue.database.notes().update(row.copy(documentState = DocumentState.STALE_AUDIO))
            assertEquals(
                TranscribeOutcome.Refused(EnqueueRefusal.DOCUMENT_NOT_VERIFIED),
                ports.transcribe(id),
            )
            assertEquals("a changed document cannot replace the pending request", job, catalogue.jobs.job(id))
        } finally {
            graph.close()
        }
    }
}
