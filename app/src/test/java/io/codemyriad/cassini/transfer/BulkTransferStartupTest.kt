package io.codemyriad.cassini.transfer

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.AppGraph
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.Sha256
import io.codemyriad.cassini.data.notes.ImportSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BulkTransferStartupTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun importWaitsForStartupRecoveryBeforeWritingFilesOrRows() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val queued = StandardTestDispatcher()
        val io = SwitchingDispatcher()
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder())
        val source = document()
        val context: Context = ApplicationProvider.getApplicationContext()
        val graph = AppGraph(
            context,
            AppGraph.Config(
                paths = paths,
                dispatchers = CassiniDispatchers(io = io),
                network = { false },
                onStartupStep = { step ->
                    if (step == AppGraph.STEP_REBUILD) {
                        entered.complete(Unit)
                        check(release.await(20, TimeUnit.SECONDS)) { "Startup gate was not released" }
                    }
                },
            ),
        )
        try {
            withTimeout(20_000) { entered.await() }
            assertFalse(graph.ready.isCompleted)
            io.delegate = queued
            graph.transfers.import(listOf(source.toUri()))
            // Run the transfer's first segment to suspension, rather than asserting before it gets CPU time.
            queued.scheduler.runCurrent()
            assertFalse("Bulk IO must wait for recovery", File(paths.cache, "bulk-transfers").exists())
            assertTrue("No note may be staged during recovery", paths.notes.listFiles().isNullOrEmpty())
            assertTrue(graph.transfers.state.value.running)
            io.delegate = Dispatchers.IO
            release.countDown()
            assertEquals(ImportSummary(imported = 1), finished(graph).summary)
            val row = graph.awaitCatalogue().database.notes().all().single()
            assertEquals(Sha256.of(source), row.sourceSha256)
            graph.transfers.import(listOf(source.toUri()))
            assertEquals(ImportSummary(duplicates = 1), finished(graph).summary)
        } finally {
            io.delegate = Dispatchers.IO
            release.countDown()
            queued.scheduler.runCurrent()
            graph.close()
        }
    }

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
        @Volatile var delegate: CoroutineDispatcher = Dispatchers.IO
        override fun dispatch(context: CoroutineContext, block: Runnable) = delegate.dispatch(context, block)
    }
}
