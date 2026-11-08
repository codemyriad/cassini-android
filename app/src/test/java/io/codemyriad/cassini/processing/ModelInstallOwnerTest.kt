package io.codemyriad.cassini.processing

import io.codemyriad.cassini.adapters.RepositoryModelAccess
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelDescriptor
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.models.ModelFetcher
import io.codemyriad.cassini.data.models.ModelRepository
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.data.settings.SettingsRepository
import io.codemyriad.cassini.engine.job.DownloadError
import io.codemyriad.cassini.engine.job.DownloadProgress
import io.codemyriad.cassini.engine.job.InstallOutcome
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

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

    @Test fun optionalRetryKeepsItsOriginalPlanWithOrWithoutEarlierSpeechConsent() = runBlocking {
        for (speechConsent in listOf(false, true)) {
            val root = folder.newFolder("optional-$speechConsent")
            val separate = AppPaths(root, File(root, "cache").apply { mkdirs() })
            val settingsScope = CoroutineScope(scope.coroutineContext + Dispatchers.IO)
            val settings = SettingsRepository.create(separate, settingsScope)
            val failedOnce = AtomicBoolean(false)
            val requested = Collections.synchronizedList(mutableListOf<ModelDescriptor>())
            val fetch = ModelFetcher { model, directory, _ ->
                requested += model
                if (model == ModelCatalog.DIARIZATION && !failedOnce.getAndSet(true)) {
                    throw java.io.IOException("Lab optional download failed")
                }
                model.artifacts.forEach { artifact ->
                    RandomAccessFile(File(directory, artifact.name), "rw").use { it.setLength(artifact.size) }
                }
            }
            val repository = ModelRepository(separate, settings, CassiniDispatchers(), fetch)
            repository.recordConsent(includeSpeech = speechConsent)
            val installer = ModelInstallOwner(repository, root, { true }, scope, Dispatchers.IO)
            val optional = setOf(ModelPart.SPEAKERS, ModelPart.VOICES)
            withTimeout(WAIT_MS) { installer.startDownload(optional).join() }
            assertTrue((installer.lastOutcome.value as InstallOutcome.Installed).failed.containsKey(ModelPart.SPEAKERS))
            withTimeout(WAIT_MS) { installer.startDownload().join() }
            assertEquals(InstallOutcome.Installed(), installer.lastOutcome.value)
            assertEquals(listOf(ModelCatalog.DIARIZATION, ModelCatalog.DIARIZATION, ModelCatalog.VOICEPRINT), requested)
            assertTrue(repository.ready(ModelPart.SPEAKERS) && repository.ready(ModelPart.VOICES))
            assertTrue(!repository.ready(ModelPart.SPEECH))
        }
    }

    @Test fun parakeetOnlyPermissionCannotFetchOptionalPartsOrScheduleThemForTheNextJob() = runBlocking {
        val separate = AppPaths(folder.newFolder("speech-root"), folder.newFolder("speech-cache"))
        val settingsScope = CoroutineScope(scope.coroutineContext + Dispatchers.IO)
        val settings = SettingsRepository.create(separate, settingsScope)
        val repository = ModelRepository(separate, settings, CassiniDispatchers(), fetcher)
        repository.recordConsent(includeOptional = false)
        val installer = ModelInstallOwner(repository, separate.modelRoot, { true }, scope, Dispatchers.IO)
        val access = RepositoryModelAccess(repository, separate.modelRoot, installer) { true }
        assertEquals(listOf(ModelPart.SPEECH), withContext(Dispatchers.IO) { access.plan().parts })
        val mixed = withTimeout(WAIT_MS) { installer.install(setOf(ModelPart.SPEECH, ModelPart.SPEAKERS)) }
        assertTrue(mixed is InstallOutcome.NeedsConsent)
        assertTrue(fetched.isEmpty())
        release.countDown()
        val speech = withTimeout(WAIT_MS) { installer.install(setOf(ModelPart.SPEECH)) }
        assertEquals(InstallOutcome.Installed(), speech)
        val expected = listOf(key(ModelCatalog.PARAKEET), key(ModelCatalog.VAD)).sorted()
        assertEquals(expected, fetched.sorted())
        assertTrue(withContext(Dispatchers.IO) { access.plan().isEmpty })
        assertTrue(installer.install(setOf(ModelPart.SPEAKERS)) is InstallOutcome.NeedsConsent)
        withTimeout(WAIT_MS) { installer.startDownload().join() }
        assertEquals("A speech-only retry cannot add optional models", expected, fetched.sorted())
        repository.recordConsent(includeSpeech = false)
        val authorized = withContext(Dispatchers.IO) { access.plan().parts }
        assertEquals(listOf(ModelPart.SPEAKERS, ModelPart.VOICES), authorized)
    }

    @Test fun offlineStandaloneOptionalSkipsStayRetryableWhileJobSkipsRemainBestEffort() = runBlocking {
        val online = AtomicBoolean(false)
        val installer = ModelInstallOwner(models, paths.modelRoot, { online.get() }, scope, Dispatchers.IO)
        val optional = setOf(ModelPart.SPEAKERS, ModelPart.VOICES)
        assertEquals(InstallOutcome.Installed(skipped = optional), installer.install(optional))
        optional.forEach { assertEquals(ModelStatus.Missing(true), models.status.value[it]) }

        withTimeout(WAIT_MS) { installer.startDownload(optional).join() }
        assertEquals(InstallOutcome.Installed(skipped = optional), installer.lastOutcome.value)
        optional.forEach {
            assertEquals(ModelStatus.Failed(Failure.DOWNLOAD, DownloadError.NO_CONNECTION.name), models.status.value[it])
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
