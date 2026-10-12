package org.cassini.data.models

import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.models.Artifact
import org.cassini.core.models.ArtifactFetcher
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.models.ModelPart
import org.cassini.core.models.ModelPlan
import org.cassini.core.models.RedirectPolicy
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.Threads
import org.cassini.data.settings.SettingsRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What Settings and the Model download screen show for one [ModelPart] (features.md sections 5 and 11). */
sealed interface ModelStatus {
    data object Ready : ModelStatus

    /** Not installed. [consented]: "Downloads with next transcription"; otherwise "Not downloaded". */
    data class Missing(val consented: Boolean) : ModelStatus

    /** [bytes] of the part's [total] download bytes are here. */
    data class Downloading(val percent: Int, val bytes: Long, val total: Long) : ModelStatus

    /** "Checking files": the downloaded files are being verified. */
    data class Verifying(val percent: Int) : ModelStatus

    /** The last install of this part failed; it tries again next time. [detail] is a diagnostic, never shown. */
    data class Failed(val failure: Failure, val detail: String?) : ModelStatus
}

/** The result of [ModelRepository.installBlocking]. [skipped] lists best-effort models that failed to install. */
data class InstallResult(val part: ModelPart, val skipped: List<ModelDescriptor>)

/** Fetches the files of one model into its directory. Tests pass a fake; the app uses [network]. */
fun interface ModelFetcher {
    /**
     * Installs [model]'s artifacts in [directory], verified. [progress] gets the bytes of the model's download done
     * so far and whether they are being checked rather than downloaded. Fails with a [UserFacingException].
     */
    fun fetch(model: ModelDescriptor, directory: File, progress: (Long, Boolean) -> Unit)

    companion object {
        /**
         * [ArtifactFetcher] with Range resume, following only the redirects [redirects] allows (architecture 5.6:
         * HTTPS to `ModelCatalog.DOWNLOAD_HOSTS`, at most five hops). [url] says where each artifact comes from.
         */
        fun network(
            redirects: RedirectPolicy = RedirectPolicy.DOWNLOADS,
            url: (Artifact) -> String = Artifact::url,
        ): ModelFetcher = ModelFetcher { model, directory, progress ->
            val download = model.download
            if (download != null) {
                ArtifactFetcher.fetchZstd(
                    url(download),
                    download,
                    model.artifacts.single(),
                    directory,
                    redirects,
                    progress,
                )
            } else {
                var completed = 0L
                for (artifact in model.artifacts) {
                    ArtifactFetcher.fetch(url(artifact), artifact, directory, redirects) { bytes, verifying ->
                        progress(completed + bytes, verifying)
                    }
                    completed += artifact.size
                }
            }
        }
    }
}

/**
 * The on-device models (docs/architecture.md, sections 4.6 and 5.6): which parts are installed under
 * [AppPaths.modelRoot], consent, the install plan and the install itself. [status] combines the readiness on disk
 * (read by [refresh]) with the progress of an install in flight, which [installBlocking] and the app's install owner
 * push. It is empty until the first [refresh]. There is no delete API: the design has no surface for it.
 *
 * Consent is `models.consentAt` present or Parakeet installed. It is recorded only by [recordConsent] (the Model
 * download screen's primary button); callers check [consented] before any install, and [installBlocking] does not.
 */
// Status, consent, plan and install of one area, as architecture 4.6 lists them.
@Suppress("TooManyFunctions")
class ModelRepository(
    private val paths: AppPaths,
    private val settings: SettingsRepository,
    private val dispatchers: CassiniDispatchers,
    private val fetcher: ModelFetcher = ModelFetcher.network(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Guards the fields below and the [state] updates. Never held across a suspension point or a download. */
    private val lock = ReentrantLock()
    private var ready: Map<ModelPart, Boolean> = emptyMap()
    private var consentRecorded = false
    private val inFlight = HashMap<ModelPart, ModelStatus>()
    private val state = MutableStateFlow<Map<ModelPart, ModelStatus>>(emptyMap())

    val status: StateFlow<Map<ModelPart, ModelStatus>> = state.asStateFlow()

    /** Reads the readiness of every part and the consent again, and returns the new [status]. */
    suspend fun refresh(): Map<ModelPart, ModelStatus> {
        val recorded = settings.consentAt.first() != null
        return withContext(dispatchers.io) {
            val readiness = readiness()
            lock.withLock {
                consentRecorded = recorded
                ready = readiness
                // A failure is shown until the part is installed or a new install starts.
                inFlight.keys.removeAll { readiness[it] == true }
                publish()
            }
        }
    }

    /** `models.consentAt` present or Parakeet installed. */
    suspend fun consented(): Boolean =
        settings.consentAt.first() != null || withContext(dispatchers.io) { ModelCatalog.PARAKEET.ready(paths.modelRoot) }

    /** "Download and transcribe" or "Download" was pressed. */
    suspend fun recordConsent() {
        settings.recordConsent(clock())
        refresh()
    }

    /** "Not now — keep the audio" was pressed. */
    suspend fun recordDeclined() = settings.recordDeclined(clock())

    /** What a download still has to install, with sizes and the free space it needs. */
    suspend fun plan(): ModelPlan = withContext(dispatchers.io) { ModelPlan.of(paths.modelRoot) }

    suspend fun ready(part: ModelPart): Boolean = withContext(dispatchers.io) { readyBlocking(part) }

    @WorkerThread
    fun readyBlocking(part: ModelPart): Boolean {
        Threads.assertOffMain("ModelRepository.readyBlocking")
        return part.primary.ready(paths.modelRoot)
    }

    /**
     * Progress of an install that the app's install owner runs or waits for ([ModelStatus.Downloading],
     * [ModelStatus.Verifying] or [ModelStatus.Failed]); shown until [clearProgress], the end of an install, or a
     * [refresh] that finds the part installed.
     */
    fun publishProgress(part: ModelPart, progress: ModelStatus) {
        require(progress !is ModelStatus.Ready && progress !is ModelStatus.Missing) { "Not a progress: $progress" }
        lock.withLock {
            inFlight[part] = progress
            publish()
        }
    }

    /** Drops the progress of [part], so it shows its readiness again (a cancelled install). */
    fun clearProgress(part: ModelPart) {
        lock.withLock {
            inFlight.remove(part)
            publish()
        }
    }

    /**
     * Installs the models of [part] that are not ready, through the [fetcher], and pushes the progress into [status]
     * and to [progress]. Each model's marker is deleted first and written only after all of its files verified. The
     * primary model is required: its failure ends the install with the [UserFacingException]
     * (CANCELLED when the thread was interrupted). An extra (the speech detector) is best effort: its failure is
     * logged and listed in [InstallResult.skipped]. For the install owner, on an IO thread (`runInterruptible`).
     */
    @WorkerThread
    fun installBlocking(part: ModelPart, progress: (ModelStatus) -> Unit = {}): InstallResult {
        Threads.assertOffMain("ModelRepository.installBlocking")
        val todo = part.models.filterNot { it.ready(paths.modelRoot) }
        val total = todo.sumOf { it.downloadBytes }.coerceAtLeast(1)
        val skipped = mutableListOf<ModelDescriptor>()
        var completed = 0L
        try {
            for (model in todo) {
                val done = completed
                val installed = installOne(part, model) { bytes, verifying ->
                    val status = progressOf(done + bytes, total, verifying)
                    publishProgress(part, status)
                    progress(status)
                }
                if (!installed) skipped += model
                completed += model.downloadBytes
            }
        } catch (error: UserFacingException) {
            lock.withLock {
                if (error.failure == Failure.CANCELLED) {
                    inFlight.remove(part)
                } else {
                    inFlight[part] = ModelStatus.Failed(error.failure, error.message)
                }
                publish()
            }
            throw error
        } finally {
            // Any other failure must not leave a download showing forever.
            lock.withLock {
                val shown = inFlight[part]
                if (shown is ModelStatus.Downloading || shown is ModelStatus.Verifying) inFlight.remove(part)
                publish()
            }
        }
        lock.withLock {
            inFlight.remove(part)
            ready = ready + (part to part.primary.ready(paths.modelRoot))
            publish()
        }
        progress(ModelStatus.Ready)
        return InstallResult(part, skipped)
    }

    /**
     * Installs one [model] through the [fetcher], with no status update: deletes its marker, fetches and verifies its
     * files (resuming `.part` files), then writes the marker. [progress] gets the bytes of the model's download and
     * whether they are being checked. Any failure is thrown: a [UserFacingException] (an I/O error becomes DOWNLOAD).
     * For the app's `ModelInstaller` store, which decides itself what is best effort, on an IO thread.
     */
    @WorkerThread
    fun installModelBlocking(model: ModelDescriptor, progress: (Long, Boolean) -> Unit) {
        Threads.assertOffMain("ModelRepository.installModelBlocking")
        val directory = model.directory(paths.modelRoot)
        directory.mkdirs()
        val marker = File(directory, model.markerFile)
        marker.delete()
        try {
            fetcher.fetch(model, directory, progress)
        } catch (error: IOException) {
            throw UserFacingException(Failure.DOWNLOAD, error.message ?: "I/O").apply { initCause(error) }
        }
        marker.writeText(model.markerValue)
    }

    /** Installs one model; false when a best-effort extra failed (and was skipped). */
    private fun installOne(part: ModelPart, model: ModelDescriptor, progress: (Long, Boolean) -> Unit): Boolean = try {
        installModelBlocking(model, progress)
        true
    } catch (error: UserFacingException) {
        if (model == part.primary || error.failure == Failure.CANCELLED) throw error
        Log.w(TAG, "Skipped ${model.directory}/${model.markerFile}: ${error.failure}", error)
        false
    }

    private fun readiness(): Map<ModelPart, Boolean> =
        ModelPart.entries.associateWith { it.primary.ready(paths.modelRoot) }

    /** The [state] from the fields; the caller holds [lock]. A part whose readiness is unknown is left out. */
    private fun publish(): Map<ModelPart, ModelStatus> {
        val consent = consentRecorded || ready[ModelPart.SPEECH] == true
        val next = ModelPart.entries.mapNotNull { part ->
            val status = inFlight[part] ?: ready[part]?.let { if (it) ModelStatus.Ready else ModelStatus.Missing(consent) }
            status?.let { part to it }
        }.toMap()
        state.value = next
        return next
    }

    private companion object {
        const val TAG = "ModelRepository"
        const val PERCENT = 100

        fun progressOf(bytes: Long, total: Long, verifying: Boolean): ModelStatus {
            val done = bytes.coerceIn(0, total)
            val percent = (done * PERCENT / total).toInt()
            return if (verifying) ModelStatus.Verifying(percent) else ModelStatus.Downloading(percent, done, total)
        }
    }
}
