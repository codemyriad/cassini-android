package io.codemyriad.cassini

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.library.GraphLibraryPorts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The gate, rather than the scheduling of a writer, decides when the library becomes accessible. */
@RunWith(RobolectricTestRunner::class)
class StartupGateTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun everyRecoveryStepKeepsTheCataloguePrivate() = runBlocking {
        val steps = listOf(
            AppGraph.STEP_REBUILD, AppGraph.STEP_METADATA, AppGraph.STEP_MODELS,
            AppGraph.STEP_RECOVER, AppGraph.STEP_RECONCILE, AppGraph.STEP_LINKS,
            AppGraph.STEP_PURGE, AppGraph.STEP_SWEEP, AppGraph.STEP_RETITLE,
        )
        for (step in steps) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val graph = graph { current ->
                if (current == step) {
                    entered.complete(Unit)
                    check(release.await(GUARD_SECONDS, TimeUnit.SECONDS)) { "Startup was not released" }
                }
            }
            try {
                withTimeout(GUARD_MS) { entered.await() }
                assertSame(graph.ready, graph.catalogue)
                val access = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    graph.awaitCatalogue().also { assertNotNull(graph.startupReport.value) }
                }
                assertFalse("Catalogue escaped at $step", access.isCompleted)
                assertFalse(graph.ready.isCompleted)
                release.countDown()
                val catalogue = withTimeout(GUARD_MS) { access.await() }
                assertSame(catalogue, graph.ready.await())
                assertNotNull(graph.startupReport.value)
                assertTrue(graph.startupReport.value!!.failed.isEmpty())
            } finally {
                release.countDown()
                graph.close()
            }
        }
    }

    @Test fun portRenameWaitsForTheLastRecoveryStepAndNonDiscardStopDoesNotDeadlock() = runBlocking {
        val paths = AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder())
        val first = graph(paths)
        val id = try {
            first.awaitCatalogue().notes.createRecording("Before recovery", 1_000, "opus")
        } finally {
            first.close()
        }
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val graph = graph(paths) { step ->
            if (step == AppGraph.STEP_RETITLE) {
                entered.complete(Unit)
                check(release.await(GUARD_SECONDS, TimeUnit.SECONDS)) { "Startup was not released" }
            }
        }
        try {
            withTimeout(GUARD_MS) { entered.await() }
            val rename = async(start = CoroutineStart.UNDISPATCHED) {
                GraphLibraryPorts(graph, context).rename(id, "After recovery")
            }
            assertFalse("The real writer must suspend at the gate", rename.isCompleted)
            // Purge's stop callback must work before publication, even when a coordinator has been created.
            withTimeout(GUARD_MS) { graph.processing.cancelAndJoin(id) }
            assertFalse(graph.ready.isCompleted)
            release.countDown()
            withTimeout(GUARD_MS) { rename.await() }
            assertEquals("After recovery", graph.awaitCatalogue().database.notes().note(id)?.title)
        } finally {
            release.countDown()
            graph.close()
        }
    }

    @Test fun databaseFailureFailsEveryAccessThroughTheSameGate() = runBlocking {
        val failure = IOException("Database unavailable")
        val graph = graph { if (it == AppGraph.STEP_DATABASE) throw failure }
        try {
            val result = runCatching { withTimeout(GUARD_MS) { graph.awaitCatalogue() } }
            val error = result.exceptionOrNull()
            assertTrue(error is IOException)
            // Coroutine stack-trace recovery may copy the exception and retain the original as its cause.
            assertSame(failure, generateSequence(error) { it.cause }.last())
            assertSame(graph.ready, graph.catalogue)
            assertEquals(setOf(AppGraph.STEP_DATABASE), graph.startupReport.value!!.failed.keys)
        } finally {
            graph.close()
        }
    }

    @Test fun closingDuringRecoveryCancelsCatalogueWaiters() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val graph = graph { step ->
            if (step == AppGraph.STEP_REBUILD) {
                entered.complete(Unit)
                check(release.await(GUARD_SECONDS, TimeUnit.SECONDS)) { "Startup was not released" }
            }
        }
        try {
            withTimeout(GUARD_MS) { entered.await() }
            val access = async(start = CoroutineStart.UNDISPATCHED) { graph.awaitCatalogue() }
            assertFalse(access.isCompleted)
            graph.appScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            // Waiter cancellation must not depend on the blocked recovery step returning.
            withTimeout(GUARD_MS) { access.join() }
            assertTrue(access.isCancelled)
            assertTrue(graph.catalogue.isCancelled)
        } finally {
            release.countDown()
            graph.close()
        }
    }

    private fun graph(
        paths: AppPaths = AppPaths(folder.newFolder(), folder.newFolder(), folder.newFolder()),
        onStep: (String) -> Unit = {},
    ) = AppGraph(
        context,
        AppGraph.Config(
            paths = paths,
            dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined),
            network = { false },
            onStartupStep = onStep,
        ),
    )

    private companion object {
        const val GUARD_SECONDS = 120L
        const val GUARD_MS = 30_000L
    }
}
