package io.codemyriad.cassini.processing

import io.codemyriad.cassini.adapters.RepositoryModelAccess
import io.codemyriad.cassini.adapters.RepositoryModelInstallBackend
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
import io.codemyriad.cassini.engine.job.InstallOutcome
import io.codemyriad.cassini.testing.NoUncaughtCoroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Independent Settings requests share one installer. Gates hold a fake fetch while another request queues. */
@RunWith(RobolectricTestRunner::class)
class ModelInstallQueueTest {
    @get:Rule(order = Int.MIN_VALUE)
    val uncaught = NoUncaughtCoroutines()

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fetched = CopyOnWriteArrayList<String>()
    private val speakersStarted = CountDownLatch(1)
    private val releaseSpeakers = CountDownLatch(1)
    private val voicesStarted = CountDownLatch(1)
    private val releaseVoices = CountDownLatch(1)
    private val failSpeakers = AtomicBoolean(false)
    private val interrupted = AtomicBoolean(false)
    private val speechStarted = CountDownLatch(1)
    private val releaseSpeech = CountDownLatch(1)
    private var blockSpeech = false
    private var fetchFailure: IOException? = null
    private lateinit var paths: AppPaths
    private lateinit var settings: SettingsRepository
    private lateinit var models: ModelRepository
    private lateinit var owner: ModelInstallOwner

    private val fetcher = ModelFetcher { model, directory, progress ->
        fetched += model.id
        fetchFailure?.let { throw it }
        if (model == ModelCatalog.PARAKEET && blockSpeech) {
            File(directory, "encoder.int8.onnx.part").writeBytes(ByteArray(1024))
            speechStarted.countDown()
            waitForRelease(releaseSpeech)
            File(directory, "encoder.int8.onnx.part").delete()
        }
        progress(model.downloadBytes / 2, false)
        if (model == ModelCatalog.DIARIZATION) {
            speakersStarted.countDown()
            waitForRelease(releaseSpeakers)
            if (failSpeakers.getAndSet(false)) throw IOException("Synthetic speaker download error")
        } else if (model == ModelCatalog.VOICEPRINT) {
            voicesStarted.countDown()
            waitForRelease(releaseVoices)
        }
        model.artifacts.forEach { artifact ->
            RandomAccessFile(File(directory, artifact.name), "rw").use { it.setLength(artifact.size) }
        }
    }

    @Before fun setUp() = runBlocking<Unit> {
        paths = AppPaths(folder.newFolder(), folder.newFolder(), modelRoot = folder.newFolder())
        settings = SettingsRepository.create(paths, CoroutineScope(scope.coroutineContext + Dispatchers.IO))
        models = ModelRepository(paths, settings, CassiniDispatchers(), fetcher)
        models.refresh()
        owner = newOwner(models)
    }

    @After fun tearDown() = runBlocking {
        releaseSpeakers.countDown()
        releaseVoices.countDown()
        releaseSpeech.countDown()
        withTimeout(WAIT_MS) { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun fallbackRetryAndTranscriptionPlanUseOnlyThePersistedPart() = runBlocking {
        releaseVoices.countDown()
        models.recordConsent(VOICES)
        // A new owner has no in-memory retry target after a process restart.
        val reopened = ModelRepository(paths, settings, CassiniDispatchers(), fetcher)
        reopened.refresh()
        val installer = newOwner(reopened)
        val access = RepositoryModelAccess(reopened, paths.modelRoot, installer) { true }
        assertEquals(listOf(ModelPart.SPEECH, ModelPart.VOICES), withContext(Dispatchers.IO) { access.plan().parts })
        assertTrue(installer.install(SPEAKERS) is InstallOutcome.NeedsConsent)
        withTimeout(WAIT_MS) { installer.startDownload().join() }
        assertEquals(listOf(ModelCatalog.VOICEPRINT.id), fetched.toList())
        assertEquals(ModelStatus.Missing(false), reopened.status.value[ModelPart.SPEAKERS])
        assertEquals(ModelStatus.Missing(false), reopened.status.value[ModelPart.SPEECH])
    }

    @Test fun secondRowShowsQueuedProgressAndDuplicateTapsJoinTheSameRequest() = runBlocking {
        models.recordConsent(SPEAKERS + VOICES)
        val first = owner.startDownload(SPEAKERS)
        assertTrue(speakersStarted.await(WAIT_S, TimeUnit.SECONDS))
        val second = owner.startDownload(VOICES)
        assertSame(second, owner.startDownload(VOICES))
        val queued = ModelStatus.Downloading(0, 0, ModelCatalog.VOICEPRINT.downloadBytes)
        assertEquals(queued, models.status.value[ModelPart.VOICES])
        assertEquals(listOf(ModelCatalog.DIARIZATION.id), fetched.toList())

        releaseSpeakers.countDown()
        releaseVoices.countDown()
        withTimeout(WAIT_MS) { joinAll(first, second) }
        assertEquals(listOf(ModelCatalog.DIARIZATION.id, ModelCatalog.VOICEPRINT.id), fetched.toList())
        assertEquals(ModelStatus.Ready, models.status.value[ModelPart.SPEAKERS])
        assertEquals(ModelStatus.Ready, models.status.value[ModelPart.VOICES])
    }

    @Test fun cancelStopsBothRequestsWithoutStartingTheQueuedVoiceModel() = runBlocking {
        models.recordConsent(SPEAKERS + VOICES)
        val first = owner.startDownload(SPEAKERS)
        assertTrue(speakersStarted.await(WAIT_S, TimeUnit.SECONDS))
        val second = owner.startDownload(VOICES)
        owner.cancel()
        withTimeout(WAIT_MS) {
            joinAll(first, second)
            owner.state.first { it == InstallState.Idle }
        }
        assertTrue(interrupted.get())
        assertEquals(listOf(ModelCatalog.DIARIZATION.id), fetched.toList())
        assertEquals(ModelStatus.Missing(true), models.status.value[ModelPart.SPEAKERS])
        assertEquals(ModelStatus.Missing(true), models.status.value[ModelPart.VOICES])
        assertNull(owner.lastOutcome.value)
    }

    @Test fun successfulSecondDownloadKeepsTheFirstFailureAndItsRetryTarget() = runBlocking {
        models.recordConsent(SPEAKERS + VOICES)
        failSpeakers.set(true)
        val first = owner.startDownload(SPEAKERS)
        assertTrue(speakersStarted.await(WAIT_S, TimeUnit.SECONDS))
        val second = owner.startDownload(VOICES)
        releaseSpeakers.countDown()
        assertTrue(voicesStarted.await(WAIT_S, TimeUnit.SECONDS))
        val failed = InstallOutcome.Installed(failed = mapOf(ModelPart.SPEAKERS to DownloadError.INTERRUPTED))
        assertEquals(failed, owner.lastOutcome.value)

        releaseVoices.countDown()
        withTimeout(WAIT_MS) { joinAll(first, second) }
        assertEquals(failed, owner.lastOutcome.value)
        assertTrue(models.status.value[ModelPart.SPEAKERS] is ModelStatus.Failed)
        assertEquals(ModelStatus.Ready, models.status.value[ModelPart.VOICES])

        withTimeout(WAIT_MS) { owner.startDownload().join() }
        val expected = listOf(ModelCatalog.DIARIZATION.id, ModelCatalog.VOICEPRINT.id, ModelCatalog.DIARIZATION.id)
        assertEquals(expected, fetched.toList())
        assertEquals(InstallOutcome.Installed(), owner.lastOutcome.value)
    }

    @Test fun transportFailuresThroughTheRepositoryStayNoConnectionOnValidatedWifi() = runBlocking {
        models.recordConsent()
        val failures = listOf(
            SocketTimeoutException("Read timed out"),
            UnknownHostException("model host"),
            ConnectException("Connection refused"),
            NoRouteToHostException("No route"),
        )
        for (failure in failures) {
            fetchFailure = failure
            withTimeout(WAIT_MS) { owner.startDownload(setOf(ModelPart.SPEECH)).join() }
            assertEquals(
                failure.javaClass.simpleName,
                DownloadError.NO_CONNECTION,
                (owner.lastOutcome.value as InstallOutcome.Failed).error,
            )
            assertEquals(
                ModelStatus.Failed(Failure.DOWNLOAD, DownloadError.NO_CONNECTION.name),
                models.status.value[ModelPart.SPEECH],
            )
        }
    }

    @Test fun anInterruptedSocketCancelThroughTheRepositoryLeavesNoFailureBanner() = runBlocking {
        models.recordConsent()
        blockSpeech = true
        val download = owner.startDownload(setOf(ModelPart.SPEECH))
        assertTrue(speechStarted.await(WAIT_S, TimeUnit.SECONDS))
        owner.cancel()
        withTimeout(WAIT_MS) { download.join() }
        assertEquals(InstallState.Idle, owner.state.value)
        assertNull(owner.lastOutcome.value)
        assertEquals(ModelStatus.Missing(consented = true), models.status.value[ModelPart.SPEECH])
        assertTrue(File(ModelCatalog.PARAKEET.directory(paths.modelRoot), "encoder.int8.onnx.part").isFile)
    }

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
            val installer = newOwner(repository, root)
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
        val installer = newOwner(repository, separate.modelRoot)
        val access = RepositoryModelAccess(repository, separate.modelRoot, installer) { true }
        assertEquals(listOf(ModelPart.SPEECH), withContext(Dispatchers.IO) { access.plan().parts })
        val mixed = withTimeout(WAIT_MS) { installer.install(setOf(ModelPart.SPEECH, ModelPart.SPEAKERS)) }
        assertTrue(mixed is InstallOutcome.NeedsConsent)
        assertTrue(fetched.isEmpty())
        releaseSpeakers.countDown()
        releaseVoices.countDown()
        val speech = withTimeout(WAIT_MS) { installer.install(setOf(ModelPart.SPEECH)) }
        assertEquals(InstallOutcome.Installed(), speech)
        val expected = listOf(ModelCatalog.PARAKEET.id, ModelCatalog.VAD.id).sorted()
        assertEquals(expected, fetched.sorted())
        assertTrue(withContext(Dispatchers.IO) { access.plan().isEmpty })
        assertTrue(installer.install(setOf(ModelPart.SPEAKERS)) is InstallOutcome.NeedsConsent)
        withTimeout(WAIT_MS) { installer.startDownload().join() }
        assertEquals("A speech-only retry cannot add optional models", expected, fetched.sorted())
        repository.recordConsent(includeSpeech = false)
        val authorized = withContext(Dispatchers.IO) { access.plan().parts }
        assertEquals(listOf(ModelPart.SPEAKERS, ModelPart.VOICES), authorized)
    }

    private fun newOwner(repository: ModelRepository, root: File = paths.modelRoot) =
        ModelInstallOwner(
            RepositoryModelInstallBackend(repository, root, { true }, Dispatchers.IO),
            scope,
            Dispatchers.IO,
        )

    private fun waitForRelease(gate: CountDownLatch) {
        try {
            check(gate.await(WAIT_S, TimeUnit.SECONDS)) { "Test did not release the model fetch" }
        } catch (error: InterruptedException) {
            interrupted.set(true)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Synthetic cancel").apply { initCause(error) }
        }
    }

    private companion object {
        val SPEAKERS = setOf(ModelPart.SPEAKERS)
        val VOICES = setOf(ModelPart.VOICES)
        const val WAIT_S = 20L
        const val WAIT_MS = 20_000L
    }
}
