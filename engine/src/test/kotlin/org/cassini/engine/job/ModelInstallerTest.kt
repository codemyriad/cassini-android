package org.cassini.engine.job

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.models.DistManifest
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.models.ModelPart
import org.cassini.core.models.ModelPlan
import org.cassini.core.models.RedirectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelInstallerTest {
    private val all = setOf(ModelPart.SPEECH, ModelPart.SPEAKERS, ModelPart.VOICES)
    private val speechReady = setOf(ModelCatalog.PARAKEET, ModelCatalog.VAD)

    @Test fun withoutConsentNothingIsFetchedAndTheNetworkIsNotAsked() = runBlocking {
        val store = FakeStore(consent = false)
        val outcome = ModelInstaller(store).install(all) { fail("No progress without consent") }
        assertEquals(InstallOutcome.NeedsConsent(ModelPlan.of { false }.totalBytes), outcome)
        assertEquals(emptyList<ModelDescriptor>(), store.attempts)
        assertEquals(0, store.networkChecks)
        assertEquals(0, store.spaceChecks)
    }

    @Test fun nothingMissingNeedsNoConsentAndNoNetwork() = runBlocking {
        val store = FakeStore(ready = speechReady + ModelCatalog.DIARIZATION + ModelCatalog.VOICEPRINT, consent = false)
        assertEquals(InstallOutcome.Installed(), ModelInstaller(store).install(all) {})
        assertEquals(0, store.consentChecks)
        assertEquals(0, store.networkChecks)
    }

    @Test fun offlineSkipsTheBestEffortPartsAtOnce() = runBlocking {
        // An install that ran would wait for the read timeout; the skip must not touch the store at all.
        val store = FakeStore(ready = speechReady, online = false)
        store.onInstall = { _, _ -> Thread.sleep(30_000) }
        val began = System.nanoTime()
        val outcome = ModelInstaller(store).install(setOf(ModelPart.SPEAKERS, ModelPart.VOICES)) {}
        assertEquals(InstallOutcome.Installed(skipped = setOf(ModelPart.SPEAKERS, ModelPart.VOICES)), outcome)
        assertEquals(emptyList<ModelDescriptor>(), store.attempts)
        assertTrue("took ${(System.nanoTime() - began) / 1_000_000} ms", System.nanoTime() - began < 1_000_000_000L)
    }

    @Test fun offlineStillTriesSpeechAndReportsNoConnection() = runBlocking {
        val store = FakeStore(online = false)
        store.failures[ModelCatalog.PARAKEET] = UnknownHostException("dist.gocassini.com")
        val outcome = ModelInstaller(store).install(all) {}
        val speech = ModelPlan.of { false }.missing.filter { it.part == ModelPart.SPEECH }
        val required = ModelPlan(speech).requiredFreeBytes
        assertEquals(InstallOutcome.Failed(ModelPart.SPEECH, DownloadError.NO_CONNECTION, required), outcome)
        assertEquals(listOf(ModelCatalog.PARAKEET), store.attempts)
    }

    @Test fun installsEveryPartInOrder() = runBlocking {
        val store = FakeStore()
        assertEquals(InstallOutcome.Installed(), ModelInstaller(store).install(all) {})
        val order = listOf(ModelCatalog.PARAKEET, ModelCatalog.VAD, ModelCatalog.DIARIZATION, ModelCatalog.VOICEPRINT)
        assertEquals(order, store.attempts)
    }

    @Test fun voicesWaitForTheDiarizer() = runBlocking {
        val store = FakeStore(ready = speechReady)
        store.failures[ModelCatalog.DIARIZATION] = UserFacingException(Failure.VERIFY)
        val outcome = ModelInstaller(store).install(all) {}
        val failed = mapOf(ModelPart.SPEAKERS to DownloadError.FILE_CHECK_FAILED)
        assertEquals(InstallOutcome.Installed(skipped = setOf(ModelPart.VOICES), failed = failed), outcome)
        assertEquals(listOf(ModelCatalog.DIARIZATION), store.attempts)
    }

    @Test fun aFailedDetectorDoesNotFailSpeech() = runBlocking {
        val store = FakeStore()
        store.failures[ModelCatalog.VAD] = UserFacingException(Failure.DOWNLOAD, "HTTP 503")
        assertEquals(InstallOutcome.Installed(), ModelInstaller(store).install(setOf(ModelPart.SPEECH)) {})
        assertEquals(listOf(ModelCatalog.PARAKEET, ModelCatalog.VAD), store.attempts)
    }

    @Test fun aMissingDetectorIsFetchedAloneWhenOnline() = runBlocking {
        val store = FakeStore(ready = setOf(ModelCatalog.PARAKEET))
        assertEquals(InstallOutcome.Installed(), ModelInstaller(store).install(setOf(ModelPart.SPEECH)) {})
        assertEquals(listOf(ModelCatalog.VAD), store.attempts)

        val offline = FakeStore(ready = setOf(ModelCatalog.PARAKEET), online = false)
        assertEquals(InstallOutcome.Installed(), ModelInstaller(offline).install(setOf(ModelPart.SPEECH)) {})
        assertEquals(emptyList<ModelDescriptor>(), offline.attempts)
    }

    @Test fun notEnoughSpaceFailsSpeechBeforeTheFirstByte() = runBlocking {
        val speech = ModelPlan(ModelPlan.of { false }.missing.filter { it.part == ModelPart.SPEECH }).requiredFreeBytes
        val store = FakeStore(usable = speech - 1)
        val outcome = ModelInstaller(store).install(all) {}
        // The figure is what speech alone needs: the best-effort parts never make the job fail.
        assertEquals(InstallOutcome.Failed(ModelPart.SPEECH, DownloadError.NOT_ENOUGH_STORAGE, speech), outcome)
        assertEquals(emptyList<ModelDescriptor>(), store.attempts)

        val enough = FakeStore(usable = ModelPlan.of { false }.requiredFreeBytes)
        assertEquals(InstallOutcome.Installed(), ModelInstaller(enough).install(all) {})
    }

    @Test fun theRoomThatSpeechNeedsAlsoFitsTheOtherParts() = runBlocking {
        // Speech's peak holds the 431 MiB encoder download, which is deleted before the speaker parts start.
        val speech = ModelPlan(ModelPlan.of { false }.missing.filter { it.part == ModelPart.SPEECH }).requiredFreeBytes
        assertEquals(InstallOutcome.Installed(), ModelInstaller(FakeStore(usable = speech)).install(all) {})
    }

    @Test fun spaceTakenDuringSpeechInstallsSpeechAndDegradesTheRest() = runBlocking {
        val speech = ModelPlan(ModelPlan.of { false }.missing.filter { it.part == ModelPart.SPEECH }).requiredFreeBytes
        val store = FakeStore(usable = speech)
        // Another app fills the phone while speech downloads: only what speech's files left stays free.
        store.onInstall = { model, _ -> if (model == ModelCatalog.PARAKEET) store.take(speech - model.artifactBytes) }
        val outcome = ModelInstaller(store).install(all) {}
        val expected = mapOf(
            ModelPart.SPEAKERS to DownloadError.NOT_ENOUGH_STORAGE,
            ModelPart.VOICES to DownloadError.NOT_ENOUGH_STORAGE,
        )
        assertEquals(InstallOutcome.Installed(failed = expected), outcome)
        assertEquals(listOf(ModelCatalog.PARAKEET, ModelCatalog.VAD), store.attempts)
    }

    @Test fun notEnoughSpaceOnlyDegradesTheBestEffortParts() = runBlocking {
        val store = FakeStore(ready = speechReady, usable = 0)
        val outcome = ModelInstaller(store).install(all) {}
        val expected = mapOf(
            ModelPart.SPEAKERS to DownloadError.NOT_ENOUGH_STORAGE,
            ModelPart.VOICES to DownloadError.NOT_ENOUGH_STORAGE,
        )
        assertEquals(InstallOutcome.Installed(failed = expected), outcome)
        assertEquals(emptyList<ModelDescriptor>(), store.attempts)
    }

    @Test fun progressCountsTheWholePartAndPassesVerifyingThrough() = runBlocking {
        val store = FakeStore()
        val parakeet = ModelCatalog.PARAKEET.downloadBytes
        val vad = ModelCatalog.VAD.downloadBytes
        store.onInstall = { model, progress ->
            val size = model.downloadBytes
            progress(size / 2, false)
            progress(size, false)
            progress(size * 4 / 5, true)
            progress(size, true)
        }
        val seen = mutableListOf<DownloadProgress>()
        ModelInstaller(store).install(setOf(ModelPart.SPEECH)) { seen += it }
        val total = parakeet + vad
        assertEquals(
            listOf(
                DownloadProgress(ModelPart.SPEECH, parakeet / 2, total, verifying = false),
                DownloadProgress(ModelPart.SPEECH, parakeet, total, verifying = false),
                DownloadProgress(ModelPart.SPEECH, parakeet * 4 / 5, total, verifying = true),
                DownloadProgress(ModelPart.SPEECH, parakeet, total, verifying = true),
                DownloadProgress(ModelPart.SPEECH, parakeet + vad / 2, total, verifying = false),
                DownloadProgress(ModelPart.SPEECH, parakeet + vad, total, verifying = false),
                DownloadProgress(ModelPart.SPEECH, parakeet + vad * 4 / 5, total, verifying = true),
                DownloadProgress(ModelPart.SPEECH, total, total, verifying = true),
            ),
            seen,
        )
        assertEquals(79, seen[2].percent)
        assertEquals(100, seen.last().percent)
    }

    @Test fun errorsMapToTheDownloadBanners() {
        val online = { true }
        val offline = { false }
        fun map(error: Throwable, network: () -> Boolean = online) = ModelInstaller.downloadError(error, network)
        val refused = try {
            RedirectPolicy.DOWNLOADS.next(URL("https://dist.gocassini.com/model"), "https://example.org/model", 1)
            error("The redirect must be refused")
        } catch (error: UserFacingException) {
            error
        }
        val tooMany = try {
            RedirectPolicy.DOWNLOADS.next(URL("https://dist.gocassini.com/model"), "https://dist.gocassini.com/b", 6)
            error("The redirect must be refused")
        } catch (error: UserFacingException) {
            error
        }
        assertEquals(DownloadError.HOST_NOT_ALLOWED, map(refused))
        assertEquals(DownloadError.HOST_NOT_ALLOWED, map(refused, offline))
        assertEquals(DownloadError.INTERRUPTED, map(tooMany))
        assertEquals(DownloadError.INTERRUPTED, map(UserFacingException(Failure.DOWNLOAD, "HTTP 503")))
        assertEquals(DownloadError.NO_CONNECTION, map(UserFacingException(Failure.DOWNLOAD, "HTTP 503"), offline))
        assertEquals(DownloadError.NOT_ENOUGH_STORAGE, map(UserFacingException(Failure.SPACE)))
        assertEquals(DownloadError.FILE_CHECK_FAILED, map(UserFacingException(Failure.VERIFY)))
        // A manifest that no longer lists the pinned model refuses the download: "File check failed".
        val unlisted = try {
            DistManifest.parse("""{"schema_version":2,"models":[],"artifacts":[]}""").urls(ModelCatalog.VAD)
            error("The manifest must refuse the model")
        } catch (error: UserFacingException) {
            error
        }
        assertEquals(DownloadError.FILE_CHECK_FAILED, map(unlisted))
        assertEquals(DownloadError.FILE_CHECK_FAILED, map(unlisted, offline))
        assertEquals(DownloadError.INSTALL, map(UserFacingException(Failure.INSTALL)))
        assertEquals(DownloadError.NO_CONNECTION, map(SocketTimeoutException("Read timed out")))
        assertEquals(DownloadError.NO_CONNECTION, map(UnknownHostException("dist.gocassini.com")))
        assertEquals(DownloadError.INTERRUPTED, map(IOException("Connection reset")))
        assertEquals(DownloadError.NO_CONNECTION, map(IOException("Connection reset"), offline))
        assertEquals(DownloadError.INSTALL, map(IllegalStateException("disk")))
    }

    @Test fun aTimeoutDuringTheSpeechDownloadIsNoConnection() = runBlocking {
        val store = FakeStore()
        store.failures[ModelCatalog.PARAKEET] = SocketTimeoutException("Read timed out")
        val outcome = ModelInstaller(store).install(all) {}
        assertEquals(DownloadError.NO_CONNECTION, (outcome as InstallOutcome.Failed).error)
        assertEquals(listOf(ModelCatalog.PARAKEET), store.attempts)
    }

    @Test fun aCancelledFetchIsNeverABestEffortFailure() {
        val store = FakeStore(ready = speechReady)
        store.failures[ModelCatalog.DIARIZATION] = UserFacingException(Failure.CANCELLED)
        try {
            runBlocking { ModelInstaller(store).install(all) {} }
            fail("A cancelled fetch must cancel the install")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf(ModelCatalog.DIARIZATION), store.attempts)
    }

    @Test fun cancellingTheCallerInterruptsTheFetch() = runBlocking {
        val store = FakeStore()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        store.onInstall = { _, _ ->
            started.countDown()
            try {
                Thread.sleep(30_000)
            } catch (error: InterruptedException) {
                interrupted.countDown()
                // ArtifactFetcher reports an interrupt between blocks this way.
                throw UserFacingException(Failure.CANCELLED).apply { initCause(error) }
            }
        }
        val install = async(Dispatchers.Default) { ModelInstaller(store).install(all) {} }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        install.cancel()
        withTimeout(5_000) {
            try {
                install.await()
                fail("The install must end cancelled")
            } catch (_: CancellationException) {
            }
        }
        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(ModelCatalog.PARAKEET), store.attempts)
    }

    private class FakeStore(
        ready: Set<ModelDescriptor> = emptySet(),
        private val consent: Boolean = true,
        private val online: Boolean = true,
        usable: Long = Long.MAX_VALUE,
    ) : ModelStore {
        /** Free bytes now: each install takes its installed files; its downloads are deleted once verified. */
        @Volatile private var free = usable
        private val installed = ready.toMutableSet()
        val attempts = mutableListOf<ModelDescriptor>()
        val failures = mutableMapOf<ModelDescriptor, Exception>()
        var onInstall: (ModelDescriptor, (Long, Boolean) -> Unit) -> Unit = { _, _ -> }
        var consentChecks = 0
        var networkChecks = 0
        var spaceChecks = 0

        override fun consented(): Boolean {
            consentChecks++
            return consent
        }

        override fun hasValidatedNetwork(): Boolean {
            networkChecks++
            return online
        }

        override fun ready(model: ModelDescriptor) = model in installed

        override fun usableBytes(): Long {
            spaceChecks++
            return free
        }

        /** Space that something else takes. */
        fun take(bytes: Long) {
            free -= bytes
        }

        override fun installBlocking(model: ModelDescriptor, progress: (Long, Boolean) -> Unit) {
            attempts += model
            failures[model]?.let { throw it }
            onInstall(model, progress)
            installed += model
            free -= model.artifactBytes
        }
    }
}
