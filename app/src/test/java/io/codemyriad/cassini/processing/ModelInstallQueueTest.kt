package io.codemyriad.cassini.processing

import io.codemyriad.cassini.adapters.RepositoryModelAccess
import io.codemyriad.cassini.core.models.ModelCatalog
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
    private lateinit var paths: AppPaths
    private lateinit var settings: SettingsRepository
    private lateinit var models: ModelRepository
    private lateinit var owner: ModelInstallOwner

    private val fetcher = ModelFetcher { model, directory, progress ->
        fetched += model.id
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

    private fun newOwner(repository: ModelRepository) =
        ModelInstallOwner(repository, paths.modelRoot, { true }, scope, Dispatchers.IO)

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
