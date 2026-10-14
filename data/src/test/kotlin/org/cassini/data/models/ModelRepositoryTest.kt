package org.cassini.data.models

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.models.ModelPart
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.db.offMain
import org.cassini.data.settings.SettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/**
 * ModelRepository (architecture 4.6 and 5.6) over a scratch model root: statuses from fake marker files, consent,
 * and installs through a fake fetcher. No real model file is read or written.
 */
@RunWith(RobolectricTestRunner::class)
class ModelRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var paths: AppPaths
    private lateinit var settingsJob: Job
    private lateinit var settings: SettingsRepository
    private val dispatchers = CassiniDispatchers(Dispatchers.IO, Dispatchers.Default, Dispatchers.Unconfined)
    private val fetched: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before fun setUp() {
        paths = AppPaths(folder.newFolder("root"), folder.newFolder("cache"), modelRoot = folder.newFolder("models"))
        settingsJob = Job()
        settings = SettingsRepository.create(paths, CoroutineScope(Dispatchers.IO + settingsJob))
    }

    @After fun tearDown() = runTest { settingsJob.cancelAndJoin() }

    private fun repository(fetcher: ModelFetcher = FakeFetcher()) =
        ModelRepository(paths, settings, dispatchers, fetcher) { 42 }

    /** Sparse files of [model]'s pinned sizes in [directory]: real lengths, no disk use. */
    private fun sparse(model: ModelDescriptor, directory: File) = model.artifacts.forEach {
        RandomAccessFile(File(directory, it.name), "rw").use { file -> file.setLength(it.size) }
    }

    /** Sparse files of the pinned sizes and the marker, as a finished install leaves them. */
    private fun install(model: ModelDescriptor, marker: String = model.markerValue) {
        val directory = model.directory(paths.modelRoot).apply { mkdirs() }
        sparse(model, directory)
        File(directory, model.markerFile).writeText(marker)
    }

    /** Writes each artifact as a sparse file and reports download and check progress, or fails for [failing]. */
    private inner class FakeFetcher(private val failing: Map<ModelDescriptor, Failure> = emptyMap()) : ModelFetcher {
        override fun fetch(model: ModelDescriptor, directory: File, progress: (Long, Boolean) -> Unit) {
            fetched += model.directory + "/" + model.markerFile
            failing[model]?.let { throw UserFacingException(it) }
            progress(model.downloadBytes / 2, false)
            progress(model.downloadBytes, false)
            progress(model.downloadBytes / 2, true)
            sparse(model, directory)
        }
    }

    @Test fun nothingInstalledAndNoConsent() = runTest {
        val repo = repository()
        assertTrue("unknown until the first refresh", repo.status.value.isEmpty())

        val status = repo.refresh()

        assertEquals(ModelPart.entries.associateWith { ModelStatus.Missing(consented = false) }, status)
        assertEquals(status, repo.status.value)
        assertFalse(repo.consented())
        assertEquals(ModelPart.entries, repo.plan().parts)
        assertEquals(730L, repo.plan().totalMebibytes)
    }

    @Test fun markerFilesDecideReadiness() = runTest {
        install(ModelCatalog.PARAKEET)
        install(ModelCatalog.DIARIZATION, marker = "another sha")
        install(ModelCatalog.VOICEPRINT)
        // A file of the wrong size is not ready, even with the right marker.
        val voiceprint = File(ModelCatalog.VOICEPRINT.directory(paths.modelRoot), ModelCatalog.voiceprintModel.name)
        RandomAccessFile(voiceprint, "rw").use { it.setLength(10) }

        val status = repository().refresh()

        assertEquals(ModelStatus.Ready, status[ModelPart.SPEECH])
        // Parakeet installed counts as consent.
        assertEquals(ModelStatus.Missing(consented = true), status[ModelPart.SPEAKERS])
        assertEquals(ModelStatus.Missing(consented = true), status[ModelPart.VOICES])
        assertTrue(repository().consented())
        assertEquals(listOf(ModelPart.SPEAKERS, ModelPart.VOICES), repository().plan().parts)
        assertTrue("the speech detector is best effort", offMain { repository().readyBlocking(ModelPart.SPEECH) })
    }

    @Test fun consentIsTheStoredTimeOrParakeet() = runTest {
        val repo = repository()
        repo.refresh()
        repo.recordDeclined()
        assertFalse(repo.consented())

        repo.recordConsent()

        assertTrue(repo.consented())
        assertEquals(42L, settings.snapshot().consentAt)
        assertEquals(42L, settings.snapshot().declinedAt)
        assertEquals(ModelStatus.Missing(consented = true), repo.status.value[ModelPart.SPEECH])
    }

    @Test fun anInstallReportsProgressAndEndsReady() = runTest {
        val repo = repository()
        repo.refresh()
        val seen = Collections.synchronizedList(mutableListOf<ModelStatus>())

        val result = offMain { repo.installBlocking(ModelPart.SPEAKERS) { seen += it } }

        assertTrue(result.skipped.isEmpty())
        assertEquals(ModelStatus.Ready, repo.status.value[ModelPart.SPEAKERS])
        assertEquals(ModelStatus.Missing(consented = false), repo.status.value[ModelPart.VOICES])
        val total = ModelCatalog.DIARIZATION.downloadBytes
        assertEquals(ModelStatus.Downloading(50, total / 2, total), seen[0])
        assertEquals(ModelStatus.Downloading(100, total, total), seen[1])
        assertEquals(ModelStatus.Verifying(50), seen[2])
        assertEquals(ModelStatus.Ready, seen.last())
        assertTrue(ModelCatalog.DIARIZATION.ready(paths.modelRoot))
        assertEquals("the voiceprint marker is separate", false, ModelCatalog.VOICEPRINT.ready(paths.modelRoot))
    }

    @Test fun aFailedExtraIsSkippedAndAFailedPrimaryFailsThePart() = runTest {
        val repo = repository(FakeFetcher(mapOf(ModelCatalog.VAD to Failure.DOWNLOAD)))
        repo.refresh()

        val speech = offMain { repo.installBlocking(ModelPart.SPEECH) }

        assertEquals(listOf(ModelCatalog.VAD), speech.skipped)
        assertEquals(ModelStatus.Ready, repo.status.value[ModelPart.SPEECH])
        assertFalse(ModelCatalog.VAD.ready(paths.modelRoot))

        val failing = repository(FakeFetcher(mapOf(ModelCatalog.VOICEPRINT to Failure.VERIFY)))
        failing.refresh()
        val error = runCatching { offMain { failing.installBlocking(ModelPart.VOICES) } }.exceptionOrNull()
        assertEquals(Failure.VERIFY, ((error?.cause ?: error) as UserFacingException).failure)
        assertEquals(ModelStatus.Failed(Failure.VERIFY, "VERIFY"), failing.status.value[ModelPart.VOICES])
        assertFalse(File(ModelCatalog.VOICEPRINT.directory(paths.modelRoot), "verified-voiceprint").exists())
        // The failure stays until the part is installed.
        assertEquals(ModelStatus.Failed(Failure.VERIFY, "VERIFY"), failing.refresh()[ModelPart.VOICES])
        install(ModelCatalog.VOICEPRINT)
        assertEquals(ModelStatus.Ready, failing.refresh()[ModelPart.VOICES])
    }

    @Test fun aCancelledInstallShowsTheReadinessAgain() = runTest {
        val repo = repository(FakeFetcher(mapOf(ModelCatalog.PARAKEET to Failure.CANCELLED)))
        repo.refresh()

        runCatching { offMain { repo.installBlocking(ModelPart.SPEECH) } }

        assertEquals(ModelStatus.Missing(consented = false), repo.status.value[ModelPart.SPEECH])
        assertEquals("no detector after a cancelled Parakeet", listOf("parakeet-ultra/verified"), fetched)
    }

    @Test fun anInstallSkipsReadyModelsAndKeepsMarkersUntilVerified() = runTest {
        install(ModelCatalog.PARAKEET)
        val repo = repository()
        repo.refresh()

        offMain { repo.installBlocking(ModelPart.SPEECH) }

        assertEquals(listOf("vad/verified"), fetched)
        assertTrue(ModelCatalog.VAD.ready(paths.modelRoot))
    }

    @Test fun theInstallOwnerPushesProgress() = runTest {
        val repo = repository()
        repo.refresh()

        repo.publishProgress(ModelPart.SPEECH, ModelStatus.Downloading(46, 46, 100))
        assertEquals(ModelStatus.Downloading(46, 46, 100), repo.status.value[ModelPart.SPEECH])
        repo.publishProgress(ModelPart.SPEECH, ModelStatus.Verifying(80))
        assertEquals(ModelStatus.Verifying(80), repo.status.value[ModelPart.SPEECH])
        repo.clearProgress(ModelPart.SPEECH)
        assertEquals(ModelStatus.Missing(consented = false), repo.status.value[ModelPart.SPEECH])
        assertTrue(runCatching { repo.publishProgress(ModelPart.SPEECH, ModelStatus.Ready) }.isFailure)
    }
}
