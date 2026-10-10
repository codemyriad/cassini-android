package org.cassini.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.DocumentState
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.models.ModelPart
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.jobs.ClaimOutcome
import org.cassini.data.jobs.EnqueueOutcome
import org.cassini.data.models.ModelFetcher
import org.cassini.engine.job.Adoption
import org.cassini.engine.job.CheckpointLoad
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Collections

/**
 * AppGraph on a scratch root (architecture 2.5 and 4.8): it builds, the startup work runs in its order and completes
 * `ready`, and the job's port adapters delegate to the `:data` repositories.
 */
@RunWith(RobolectricTestRunner::class)
class AppGraphTest {
    @get:Rule val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graphs = mutableListOf<AppGraph>()
    private val steps: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var paths: AppPaths

    private val fixture = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.opus")

    private fun graph(
        root: AppPaths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder()),
        online: Boolean = true,
    ): AppGraph {
        paths = root
        val config = AppGraph.Config(
            paths = root,
            dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
            fetcher = ModelFetcher { _, _, _ -> fail("No model is fetched in this test") },
            network = { online },
            onStartupStep = { steps += it },
        )
        return AppGraph(context, config).also { graphs += it }
    }

    @After fun tearDown() = graphs.forEach(AppGraph::close)

    private fun AppGraph.awaitReady() = runBlocking { withTimeout(TIMEOUT_MS) { ready.await() } }

    @Test fun theGraphBuildsOnAScratchRootAndReadyCompletes() {
        val graph = graph()
        graph.awaitReady()

        assertTrue(graph.catalogue.isCompleted)
        assertTrue("the database is under the scratch root", paths.database.isFile)
        assertFalse(File(context.filesDir, "cassini.db").exists())
        val report = runBlocking { graph.startupReport.first { it != null } }
        assertEquals(emptyMap<String, String>(), report?.failed)
    }

    @Test fun theStartupStepsRunInTheirOrderRecoverBeforeReconcile() {
        graph().awaitReady()

        assertEquals(
            listOf(
                AppGraph.STEP_DATABASE,
                AppGraph.STEP_REBUILD,
                AppGraph.STEP_MODELS,
                AppGraph.STEP_RECOVER,
                AppGraph.STEP_RECONCILE,
                AppGraph.STEP_LINKS,
                AppGraph.STEP_PURGE,
                AppGraph.STEP_SWEEP,
                AppGraph.STEP_RETITLE,
            ),
            steps.toList(),
        )
    }

    @Test fun theNextStartRecoversALiveCaptureAndRequeuesARunningJob() = runBlocking {
        val first = graph()
        first.awaitReady()
        val catalogue = first.awaitCatalogue()
        val live = catalogue.notes.createRecording("Live", 1_000, "opus")
        fixture.copyTo(File(paths.note(live), "capture.opus"))
        val plain = plainNote(first, "Riunione")
        catalogue.jobs.enqueue(plain, JobKind.TRANSCRIBE)
        assertTrue(catalogue.jobs.claimNext() is ClaimOutcome.Claimed)
        first.close()
        graphs.remove(first)

        // Process death: a new graph over the same files.
        val second = graph(paths)
        second.awaitReady()
        val report = requireNotNull(second.startupReport.value)
        assertEquals(listOf(live), report.recovered)
        assertEquals(listOf(plain), report.reconcile?.requeued)
        assertEquals(AudioKind.PLAIN, second.awaitCatalogue().database.notes().note(live)?.audioKind)
    }

    @Test fun theStoragePortsDelegateFromTheRowToTheAdopt() = runBlocking {
        val graph = graph()
        graph.awaitReady()
        val catalogue = graph.awaitCatalogue()
        val storage = catalogue.jobStorage
        val id = plainNote(graph, "Riunione")
        assertTrue(catalogue.jobs.enqueue(id, JobKind.TRANSCRIBE) is EnqueueOutcome.Queued)
        assertTrue(catalogue.jobs.claimNext() is ClaimOutcome.Claimed)

        assertEquals(File(paths.note(id), "capture.opus"), storage.audioFile(id))
        assertEquals(DURATION_MS, storage.knownDurationMs(id))
        assertEquals(CheckpointLoad.Missing, storage.loadCheckpoint(id))
        val work = withContext(Dispatchers.IO) { storage.workFiles(id) }
        assertTrue(paths.work(id).isDirectory)
        assertEquals(File(paths.work(id), "audio.opus.part"), work.encodedAudio)
        assertEquals(File(paths.work(id), "sealed.opus"), storage.sealTarget(id))

        // Draft names: the job's change, then the gate at step 6.
        val named = withContext(Dispatchers.IO) { storage.mutateNames(id) { it.copy(labels = mapOf("S1" to "Luca")) } }
        assertEquals(mapOf("S1" to "Luca"), named.labels)
        val closed = withContext(Dispatchers.IO) { storage.closeNames(id) }
        assertEquals(named, closed)
        assertFalse(graph.namingGate.isOpen(id))
        val guarded = graph.namingGate.guard(id) { it.copy(labels = emptyMap()) }
        val refused = catalogue.checkpoints.names.mutate(id, guarded)
        assertEquals(named, refused)
        storage.openNames(id)
        assertTrue(graph.namingGate.isOpen(id))
        assertEquals(named, withContext(Dispatchers.IO) { storage.names(id).get() })

        // The seal as the job does it, then the adopt.
        val audio = storage.audioFile(id)
        val sealed = withContext(Dispatchers.IO) {
            storage.withNoteLockBlocking(id) {
                assertNull(storage.existingDocumentBlocking(id))
                val title = storage.titleBlocking(id)
                CassiniDocument.create(
                    audio,
                    Transcript(listOf(Word("S1", 0, 400, "ciao"), Word("S2", 500, 900, "allora"))),
                    title,
                    JSONObject().put("engine", "test"),
                    null,
                    named.labels,
                    storage.sealTarget(id),
                )
            }.also { storage.markSealedBlocking(id, it) }
        }
        assertEquals(DocumentState.OK, sealed.documentState)
        storage.adopt(id, Adoption(named.labels, emptyMap(), generation = 0, finalizationMs = 1_234))

        val row = requireNotNull(catalogue.database.notes().note(id))
        assertEquals(AudioKind.DOCUMENT, row.audioKind)
        assertEquals(DocumentState.OK, row.documentState)
        assertFalse(paths.work(id).exists())
        assertFalse(File(paths.note(id), "capture.opus").exists())
        assertNull(catalogue.jobs.job(id))
        assertEquals(1_234L, graph.settings.finalizationMs.first())
        assertNotNull(withContext(Dispatchers.IO) { storage.existingDocumentBlocking(id) })
    }

    @Test fun theVoiceAndModelPortsDelegate() = runBlocking {
        val graph = graph(online = false)
        graph.awaitReady()
        val catalogue = graph.awaitCatalogue()
        val id = plainNote(graph, "Riunione")
        val voices = catalogue.jobVoices
        val models = graph.modelAccess

        withContext(Dispatchers.IO) {
            assertEquals(0L, voices.generationBlocking())
            assertTrue(voices.peopleBlocking().isEmpty())
            assertTrue(voices.noteVoicesBlocking(id).isEmpty())
            val wrongModel = runCatching { voices.savePendingBlocking(id, "v1", "other", emptyMap(), 0) }
            assertTrue(wrongModel.exceptionOrNull() is IllegalArgumentException)
            voices.discardPendingBlocking(id)

            assertFalse(models.ready(ModelPart.SPEECH))
            assertFalse(models.detectorReady())
            assertEquals(ModelPart.entries.toSet(), models.plan().missing.map { it.part }.toSet())
            assertFalse(models.hasValidatedNetwork())
        }
        assertFalse(models.consented())
        graph.models.recordConsent()
        assertTrue(models.consented())
    }

    /** A PLAIN note over a copy of the FLEURS clip, as a finished recording leaves it. */
    private suspend fun plainNote(graph: AppGraph, title: String): String {
        val notes = graph.awaitCatalogue().notes
        val id = notes.createRecording(title, 1_000, "opus")
        fixture.copyTo(File(paths.note(id), "capture.opus"))
        assertTrue(notes.finishRecording(id, DURATION_MS))
        return id
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val DURATION_MS = 4_000L
    }
}
