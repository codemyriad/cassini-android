package org.cassini.android.processing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cassini.core.Failure
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.models.ModelPart
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.models.ModelFetcher
import org.cassini.data.models.ModelRepository
import org.cassini.data.models.ModelStatus
import org.cassini.data.settings.SettingsRepository
import org.cassini.engine.job.DownloadError
import org.cassini.engine.job.DownloadProgress
import org.cassini.engine.job.InstallOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ModelInstallOwner (architecture 5.6): one install at a time, a request for the same parts joins it, a request for
 * other parts waits for it, and every artifact is fetched once. The fetcher is a fake that writes sparse files of the
 * pinned sizes, so nothing is downloaded and almost no disk is used.
 */
@RunWith(RobolectricTestRunner::class)
class ModelInstallOwnerTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fetched: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val interrupted = AtomicBoolean(false)

    /** The fetch ignores an interrupt, as a socket read blocked until its timeout does. */
    private val stubborn = AtomicBoolean(false)
    private val inFlight = AtomicInteger()
    private val mostInFlight = AtomicInteger()
    private lateinit var paths: AppPaths
    private lateinit var models: ModelRepository
    private lateinit var owner: ModelInstallOwner

    /** Blocks the fetch of Parakeet until [release], after it wrote a `.part` file; records every fetch. */
    private val fetcher = ModelFetcher { model, directory, progress ->
        fetched += key(model)
        mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
        try {
            if (model == ModelCatalog.PARAKEET) {
                File(directory, "encoder.int8.onnx.part").writeBytes(ByteArray(PART_BYTES))
                started.countDown()
                awaitRelease()
                File(directory, "encoder.int8.onnx.part").delete()
            }
            model.artifacts.forEach { artifact ->
                RandomAccessFile(File(directory, artifact.name), "rw").use { it.setLength(artifact.size) }
            }
            progress(model.downloadBytes, false)
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun awaitRelease() {
        while (true) {
            try {
                check(release.await(WAIT_S, TimeUnit.SECONDS)) { "The test never released the fetch" }
                return
            } catch (error: InterruptedException) {
                interrupted.set(true)
                if (!stubborn.get()) throw error
            }
        }
    }

    @Before fun setUp() = runBlocking {
        paths = AppPaths(folder.newFolder("root"), folder.newFolder("cache"), modelRoot = folder.newFolder("models"))
        val dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined)
        val settings = SettingsRepository.create(paths, CoroutineScope(scope.coroutineContext + Dispatchers.IO))
        models = ModelRepository(paths, settings, dispatchers, fetcher)
        models.recordConsent()
        owner = ModelInstallOwner(models, paths.modelRoot, { true }, scope, Dispatchers.IO)
    }

    /** Waits until the work of [scope] has ended, before TemporaryFolder deletes its files. */
    @After fun tearDown() = runBlocking {
        release.countDown()
        withTimeout(WAIT_MS) { scope.coroutineContext.job.cancelAndJoin() }
    }

    private fun key(model: ModelDescriptor) = "${model.directory}/${model.markerFile}"

    private val everyModel = ModelPart.entries.flatMap { it.models }.map(::key).sorted()

    @Test fun aJobJoiningTheStandaloneDownloadFetchesEachArtifactOnce() = runBlocking {
        owner.startDownload()
        assertTrue(started.await(WAIT_S, TimeUnit.SECONDS))
        val progress = AtomicInteger()
        val job = async { owner.install(ModelPart.entries.toSet()) { progress.incrementAndGet() } }
        val joined = withTimeout(WAIT_MS) {
            owner.state.first { it is InstallState.Installing && it.waiters == 2 } as InstallState.Installing
        }
        assertTrue(joined.standalone)

        release.countDown()
        assertEquals(InstallOutcome.Installed(), withTimeout(WAIT_MS) { job.await() })
        assertEquals(everyModel, fetched.sorted())
        assertTrue("the job saw the shared progress", progress.get() > 0)
        withTimeout(WAIT_MS) { owner.state.first { it == InstallState.Idle } }
        assertEquals(ModelPart.entries.associateWith { ModelStatus.Ready }, models.status.value)
    }

    @Test fun aRequestForOtherPartsWaitsAndFetchesNothingTwice() = runBlocking {
        owner.startDownload(setOf(ModelPart.SPEECH))
        assertTrue(started.await(WAIT_S, TimeUnit.SECONDS))
        val job = async { owner.install(ModelPart.entries.toSet()) }
        delay(SETTLE_MS)
        assertEquals("the second install waits for the first", listOf(key(ModelCatalog.PARAKEET)), fetched.toList())

        release.countDown()
        assertEquals(InstallOutcome.Installed(), withTimeout(WAIT_MS) { job.await() })
        assertEquals(everyModel, fetched.sorted())
    }

    @Test fun cancelEndsEveryWaitAndKeepsThePartFiles() = runBlocking {
        val download = owner.startDownload()
        assertTrue(started.await(WAIT_S, TimeUnit.SECONDS))
        val job = async { runCatching { owner.install(ModelPart.entries.toSet()) } }
        withTimeout(WAIT_MS) { owner.state.first { it is InstallState.Installing && it.waiters == 2 } }

        owner.cancel()
        assertTrue(withTimeout(WAIT_MS) { job.await() }.exceptionOrNull() is CancellationException)
        withTimeout(WAIT_MS) { download.join() }
        withTimeout(WAIT_MS) { owner.state.first { it == InstallState.Idle } }
        assertTrue(interrupted.get())
        assertTrue(File(ModelCatalog.PARAKEET.directory(paths.modelRoot), "encoder.int8.onnx.part").isFile)
        assertEquals(ModelStatus.Missing(consented = true), models.status.value[ModelPart.SPEECH])
    }

    @Test fun aRequestRightAfterACancelWaitsForTheCancelledFetchToEnd() = runBlocking {
        stubborn.set(true)
        owner.startDownload()
        assertTrue(started.await(WAIT_S, TimeUnit.SECONDS))

        owner.cancel()
        val again = async { owner.install(ModelPart.entries.toSet()) }
        delay(SETTLE_MS)
        assertTrue(interrupted.get())
        assertEquals("no second fetch while the first one runs", listOf(key(ModelCatalog.PARAKEET)), fetched.toList())

        release.countDown()
        assertEquals(InstallOutcome.Installed(), withTimeout(WAIT_MS) { again.await() })
        assertEquals("one fetch at a time", 1, mostInFlight.get())
        assertEquals(ModelPart.entries.associateWith { ModelStatus.Ready }, models.status.value)
    }

    @Test fun theInstallStopsWhenItsLastWaiterLeaves() = runBlocking {
        val job = scope.launch { owner.install(setOf(ModelPart.SPEECH)) }
        assertTrue(started.await(WAIT_S, TimeUnit.SECONDS))
        job.cancelAndJoin()

        withTimeout(WAIT_MS) { owner.state.first { it == InstallState.Idle } }
        assertTrue(interrupted.get())
        assertEquals(listOf(key(ModelCatalog.PARAKEET)), fetched.toList())
    }

    @Test fun anInstallCancelledBeforeItsBodyRunsLeavesTheOwnerIdle() = runBlocking<Unit> {
        // The only IO thread is busy, so the install is cancelled before its body is dispatched.
        val gate = CountDownLatch(1)
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        io.executor.execute { gate.await(WAIT_S, TimeUnit.SECONDS) }
        try {
            val gated = ModelInstallOwner(models, paths.modelRoot, { true }, scope, io)
            val download = gated.startDownload(setOf(ModelPart.SPEECH))
            withTimeout(WAIT_MS) { gated.state.first { it is InstallState.Installing } }
            gated.cancel()
            gate.countDown()
            withTimeout(WAIT_MS) { download.join() }
            withTimeout(WAIT_MS) { gated.state.first { it == InstallState.Idle } }
            assertTrue("the cancelled install fetched nothing", fetched.isEmpty())

            release.countDown()
            assertEquals(InstallOutcome.Installed(), withTimeout(WAIT_MS) { gated.install(setOf(ModelPart.SPEECH)) })
            assertEquals(ModelPart.SPEECH.models.map(::key).sorted(), fetched.sorted())
            withTimeout(WAIT_MS) { gated.state.first { it == InstallState.Idle } }
        } finally {
            gate.countDown()
            io.close()
        }
    }

    @Test fun downloadErrorsAndProgressMapToTheModelStatus() {
        assertEquals(Failure.SPACE, ModelInstallOwner.failureOf(DownloadError.NOT_ENOUGH_STORAGE))
        val progress = DownloadProgress(ModelPart.SPEECH, doneBytes = 50, totalBytes = 200, verifying = false)
        val status = ModelInstallOwner.statusOf(progress)
        assertEquals(ModelStatus.Downloading(25, 50, 200), status)
    }

    private companion object {
        const val PART_BYTES = 1024
        const val WAIT_S = 20L
        const val WAIT_MS = 20_000L
        const val SETTLE_MS = 300L
    }
}
