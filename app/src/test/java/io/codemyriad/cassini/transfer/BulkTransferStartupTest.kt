package io.codemyriad.cassini.transfer

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.Sha256
import io.codemyriad.cassini.data.notes.ImportOutcome
import io.codemyriad.cassini.data.notes.ImportSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BulkTransferStartupTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun importWaitsForStartupRecoveryBeforeWritingFilesOrRows() = runBlocking {
        verifyStartupGate(document(), external = false, title = "External")
    }

    @Test fun externalDocumentWaitsForRecoveryAndKeepsItsTitleAndDigest() = runBlocking {
        verifyStartupGate(document(), external = true, title = "External")
    }

    @Test fun externalAudioWaitsForRecoveryAndKeepsItsFilenameTitleAndDigest() = runBlocking {
        val source = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.wav")
        verifyStartupGate(source, external = true, title = "fleurs-it-short")
    }

    @Test fun aDatabaseOpenFailureStillEndsTheExternalImportWithAnOpenError() = runBlocking {
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder())
        val graph = AppGraph(
            ApplicationProvider.getApplicationContext(),
            AppGraph.Config(
                paths = paths,
                network = { false },
                onStartupStep = { step -> if (step == AppGraph.STEP_DATABASE) throw IOException("Database unavailable") },
            ),
        )
        try {
            val result = graph.notices.imports.start(document().toUri().toString())
            assertNull(withTimeout(20_000) { result.await() })
            assertEquals(Failure.OPEN, graph.notices.imports.state.value.failure?.failure)
            assertTrue(graph.notices.imports.state.value.running.isEmpty())
            assertTrue(paths.notes.listFiles().isNullOrEmpty())
        } finally {
            graph.close()
        }
    }

    private suspend fun verifyStartupGate(source: File, external: Boolean, title: String) {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val queued = StandardTestDispatcher()
        val io = SwitchingDispatcher()
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder())
        val graph = startupGraph(paths, io, entered, release)
        try {
            withTimeout(20_000) { entered.await() }
            assertFalse(graph.ready.isCompleted)
            io.use(queued)
            val pending = if (external) {
                graph.notices.imports.start(source.toUri().toString())
            } else {
                graph.transfers.import(listOf(source.toUri()))
                null
            }
            // Run the transfer's first segment to suspension, rather than asserting before it gets CPU time.
            queued.scheduler.runCurrent()
            assertFalse("Bulk IO must wait for recovery", File(paths.cache, "bulk-transfers").exists())
            assertTrue("No note may be staged during recovery", paths.notes.listFiles().isNullOrEmpty())
            if (pending != null) {
                assertEquals(listOf(source.name), graph.notices.imports.state.value.running)
                assertFalse(pending.isCompleted)
            } else {
                assertTrue(graph.transfers.state.value.running)
            }
            // Force work into the reported window after the first drain but before switching back to IO.
            val lateIo = CompletableDeferred<Unit>()
            graph.appScope.launch(io) { lateIo.complete(Unit) }
            io.use(Dispatchers.IO)
            queued.scheduler.runCurrent()
            assertTrue("IO queued during handoff must run before releasing recovery", lateIo.isCompleted)
            release.countDown()
            val outcome = pending?.let { withTimeout(20_000) { it.await() } }
            if (!external) assertEquals(ImportSummary(imported = 1), finished(graph).summary)
            val row = graph.awaitCatalogue().database.notes().all().single()
            if (external) assertEquals(ImportOutcome.Created(row.id), outcome)
            assertEquals(title, row.title)
            assertEquals(Sha256.of(source), row.sourceSha256)
            if (external) {
                val duplicate = graph.notices.imports.start(source.toUri().toString())
                assertEquals(ImportOutcome.Existing(row.id), withTimeout(20_000) { duplicate.await() })
            } else {
                graph.transfers.import(listOf(source.toUri()))
                assertEquals(ImportSummary(duplicates = 1), finished(graph).summary)
            }
        } finally {
            io.use(Dispatchers.IO)
            queued.scheduler.runCurrent()
            release.countDown()
            graph.close()
        }
    }

    private fun startupGraph(
        paths: AppPaths,
        io: SwitchingDispatcher,
        entered: CompletableDeferred<Unit>,
        release: CountDownLatch,
    ) = AppGraph(
        ApplicationProvider.getApplicationContext<Context>(),
        AppGraph.Config(
            paths = paths,
            dispatchers = CassiniDispatchers(io = io, default = io),
            network = { false },
            onStartupStep = { step ->
                if (step == AppGraph.STEP_REBUILD) {
                    entered.complete(Unit)
                    check(release.await(20, TimeUnit.SECONDS)) { "Startup gate was not released" }
                }
            },
        ),
    )

    private suspend fun finished(graph: AppGraph) = withTimeout(20_000) {
        graph.transfers.state.first { !it.running }
    }

    private fun document(): File {
        val fixture = File(checkNotNull(System.getProperty("cassini.fixtures")), "audio/fleurs-it-short.opus")
        return folder.newFile("external.opus").apply {
            writeBytes(
                CassiniDocument.create(
                    fixture.readBytes(),
                    Transcript(listOf(Word("S1", 0, 100, "Hello")), "en"),
                    "External",
                    JSONObject(),
                ),
            )
        }
    }

    private class SwitchingDispatcher : CoroutineDispatcher() {
        private var delegate: CoroutineDispatcher = Dispatchers.IO
        @Synchronized fun use(next: CoroutineDispatcher) { delegate = next }
        @Synchronized override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, block)
    }
}
