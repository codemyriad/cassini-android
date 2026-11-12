package io.codemyriad.cassini.processing

import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelDescriptor
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.engine.job.DownloadError
import io.codemyriad.cassini.engine.job.DownloadProgress
import io.codemyriad.cassini.engine.job.InstallOutcome
import io.codemyriad.cassini.engine.job.ModelInstaller
import io.codemyriad.cassini.engine.job.ModelStore
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * ModelInstallOwner (architecture 5.6): one install at a time, a request for the same parts joins it, a request for
 * other parts waits for it, and every artifact is fetched once. The fetcher is a fake that writes sparse files of the
 * pinned sizes, so nothing is downloaded and almost no disk is used.
 */
class ModelInstallOwnerTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fetched: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val interrupted = AtomicBoolean(false)
    private val interruptSeen = CountDownLatch(1)

    /** The fetch ignores an interrupt, as a socket read blocked until its timeout does. */
    private val stubborn = AtomicBoolean(false)
    private val inFlight = AtomicInteger()
    private val mostInFlight = AtomicInteger()
    private lateinit var modelRoot: File
    private lateinit var models: FakeModels
    private lateinit var owner: ModelInstallOwner

    /** Blocks the fetch of Parakeet until [release], after it wrote a `.part` file; records every fetch. */
    private val fetcher: (ModelDescriptor, File, (Long, Boolean) -> Unit) -> Unit = { model, directory, progress ->
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
                interruptSeen.countDown()
                if (stubborn.get()) continue
                throw error
            }
        }
    }

    @Before fun setUp() = runBlocking {
        modelRoot = folder.newFolder("models")
        models = FakeModels()
        owner = newOwner()
    }

    /** Waits until the work of [scope] has ended, before TemporaryFolder deletes its files. */
    @After fun tearDown() = runBlocking {
        release.countDown()
        withTimeout(WAIT_MS) { scope.coroutineContext.job.cancelAndJoin() }
    }

    private fun key(model: ModelDescriptor) = "${model.directory}/${model.markerFile}"

    private val everyModel = ModelPart.entries.flatMap { it.models }.map(::key).sorted()

    @Test fun offlineStandaloneOptionalSkipsStayRetryableWhileJobSkipsRemainBestEffort() = runBlocking {
        val online = AtomicBoolean(false)
        val installer = newOwner(online = { online.get() })
        val optional = setOf(ModelPart.SPEAKERS, ModelPart.VOICES)
        assertEquals(InstallOutcome.Installed(skipped = optional), installer.install(optional))
        optional.forEach { assertEquals(ModelStatus.Missing(true), models.status.value[it]) }

        withTimeout(WAIT_MS) { installer.startDownload(optional).join() }
        assertEquals(InstallOutcome.Installed(skipped = optional), installer.lastOutcome.value)
        optional.forEach {
            assertEquals(
                ModelStatus.Failed(Failure.DOWNLOAD, DownloadError.NO_CONNECTION.name),
                models.status.value[it],
            )
        }
        assertTrue(fetched.isEmpty())

        online.set(true)
        withTimeout(WAIT_MS) { installer.startDownload().join() }
        assertEquals(InstallOutcome.Installed(), installer.lastOutcome.value)
        optional.forEach { assertEquals(ModelStatus.Ready, models.status.value[it]) }
        assertEquals(listOf(key(ModelCatalog.DIARIZATION), key(ModelCatalog.VOICEPRINT)), fetched)
    }

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
        val io = ownerWithObservableDispatch()
        owner.startDownload(setOf(ModelPart.SPEECH))
        assertTrue(started.await(WAIT_S, TimeUnit.SECONDS))
        val dispatches = io.dispatches.get()
        val job = async(start = CoroutineStart.UNDISPATCHED) { owner.install(ModelPart.entries.toSet()) }
        assertTrue("the second request is waiting", job.isActive)
        assertEquals("no second install was dispatched", dispatches, io.dispatches.get())
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
        assertTrue(File(ModelCatalog.PARAKEET.directory(modelRoot), "encoder.int8.onnx.part").isFile)
        assertEquals(ModelStatus.Missing(consented = true), models.status.value[ModelPart.SPEECH])
    }

    @Test fun aRequestRightAfterACancelWaitsForTheCancelledFetchToEnd() = runBlocking {
        val io = ownerWithObservableDispatch()
        stubborn.set(true)
        owner.startDownload()
        assertTrue(started.await(WAIT_S, TimeUnit.SECONDS))

        owner.cancel()
        val dispatches = io.dispatches.get()
        val again = async(start = CoroutineStart.UNDISPATCHED) { owner.install(ModelPart.entries.toSet()) }
        assertTrue("the replacement request is waiting", again.isActive)
        assertEquals("no replacement install was dispatched", dispatches, io.dispatches.get())
        assertTrue("the fetch observed its interrupt", interruptSeen.await(WAIT_S, TimeUnit.SECONDS))
        assertTrue(interrupted.get())
        assertEquals("no second fetch while the first one runs", listOf(key(ModelCatalog.PARAKEET)), fetched.toList())

        release.countDown()
        assertEquals(InstallOutcome.Installed(), withTimeout(WAIT_MS) { again.await() })
        assertEquals("one fetch at a time", 1, mostInFlight.get())
        assertEquals(ModelPart.entries.associateWith { ModelStatus.Ready }, models.status.value)
    }

    @Test fun retryOfTheSamePartCanQueueWhileTheCancelledFetchUnwinds() = runBlocking {
        ownerWithObservableDispatch()
        stubborn.set(true)
        val parts = setOf(ModelPart.SPEECH)
        val cancelled = owner.startDownload(parts)
        assertTrue(started.await(WAIT_S, TimeUnit.SECONDS))
        owner.cancel()
        assertTrue(interruptSeen.await(WAIT_S, TimeUnit.SECONDS))

        val retry = owner.startDownload(parts)
        assertNotSame(cancelled, retry)
        assertTrue(retry.isActive)
        assertEquals(listOf(key(ModelCatalog.PARAKEET)), fetched.toList())

        release.countDown()
        withTimeout(WAIT_MS) {
            cancelled.join()
            retry.join()
        }
        assertEquals(InstallOutcome.Installed(), owner.lastOutcome.value)
        assertEquals(ModelStatus.Ready, models.status.value[ModelPart.SPEECH])
        assertEquals(1, mostInFlight.get())
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
            val gated = newOwner(io = io)
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

    /** Unconfined attachment runs through its first wait before returning; file work remains on real IO. */
    private fun ownerWithObservableDispatch(): ObservedIo {
        val io = ObservedIo()
        val attaching = CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined)
        owner = newOwner(io = io, attaching = attaching)
        return io
    }

    private fun newOwner(
        io: CoroutineDispatcher = Dispatchers.IO,
        attaching: CoroutineScope = scope,
        online: () -> Boolean = { true },
    ) = ModelInstallOwner(models.backend(io, online), attaching, io)

    /** Only effects are faked: the owner and ModelInstaller run their production sequencing and interruption. */
    private inner class FakeModels : ModelInstallBackend {
        override val status = MutableStateFlow<Map<ModelPart, ModelStatus>>(
            ModelPart.entries.associateWith { ModelStatus.Missing(true) },
        )
        private val progress = mutableMapOf<ModelPart, ModelStatus>()
        override suspend fun consented(part: ModelPart): Boolean = true
        override fun hasValidatedNetwork(): Boolean = true

        @Synchronized override fun publishProgress(part: ModelPart, progress: ModelStatus) {
            this.progress[part] = progress
            snapshot()
        }

        @Synchronized override fun clearProgress(part: ModelPart) {
            progress.remove(part)
            snapshot()
        }
        override suspend fun refresh() = snapshot()

        @Synchronized private fun snapshot() {
            status.value = ModelPart.entries.associateWith { part ->
                if (part.primary.ready(modelRoot)) ModelStatus.Ready else progress[part] ?: ModelStatus.Missing(true)
            }
        }
        override suspend fun install(parts: Set<ModelPart>, onProgress: (DownloadProgress) -> Unit): InstallOutcome =
            installWith(parts, onProgress, Dispatchers.IO) { true }

        fun backend(io: CoroutineDispatcher, online: () -> Boolean): ModelInstallBackend =
            object : ModelInstallBackend by this {
                override fun hasValidatedNetwork(): Boolean = online()
                override suspend fun install(
                    parts: Set<ModelPart>,
                    onProgress: (DownloadProgress) -> Unit,
                ): InstallOutcome =
                    installWith(parts, onProgress, io, online)
            }

        private suspend fun installWith(
            parts: Set<ModelPart>,
            onProgress: (DownloadProgress) -> Unit,
            io: CoroutineDispatcher,
            online: () -> Boolean,
        ): InstallOutcome {
            val store = object : ModelStore {
                override fun consented(): Boolean = true
                override fun hasValidatedNetwork(): Boolean = online()
                override fun ready(model: ModelDescriptor): Boolean = model.ready(modelRoot)
                override fun usableBytes(): Long = Long.MAX_VALUE
                override fun installBlocking(model: ModelDescriptor, progress: (Long, Boolean) -> Unit) {
                    if (ready(model)) return
                    val directory = model.directory(modelRoot).apply { mkdirs() }
                    fetcher(model, directory, progress)
                    File(directory, model.markerFile).writeText(model.markerValue)
                }
            }
            return ModelInstaller(store, io).install(parts, onProgress)
        }
    }

    /** Count an attempted dispatch even if its worker has not got CPU time yet. */
    private class ObservedIo : CoroutineDispatcher() {
        val dispatches = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            Dispatchers.IO.dispatch(context, block)
        }
    }

    private companion object {
        const val PART_BYTES = 1024
        const val WAIT_S = 20L
        const val WAIT_MS = 20_000L
    }
}
