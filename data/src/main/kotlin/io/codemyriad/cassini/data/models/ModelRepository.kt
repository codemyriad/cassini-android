package io.codemyriad.cassini.data.models

import android.util.Log
import androidx.annotation.WorkerThread
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.UserFacingException
import io.codemyriad.cassini.core.models.ArtifactFetcher
import io.codemyriad.cassini.core.models.DistManifest
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelDescriptor
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.ModelPlan
import io.codemyriad.cassini.core.models.RedirectPolicy
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.Threads
import io.codemyriad.cassini.data.notes.NoteFiles
import io.codemyriad.cassini.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The result of [ModelRepository.installBlocking]. [skipped] lists best-effort models that failed to install. */
data class InstallResult(val part: ModelPart, val skipped: List<ModelDescriptor>)

/** Fetches the files of one model into its directory. Tests pass a fake; the app uses [network]. */
fun interface ModelFetcher {
    /**
     * Installs [model]'s artifacts in [directory], verified. [progress] gets the bytes of the model's download done
     * so far and whether they are being checked rather than downloaded. Fails with a [UserFacingException].
     */
    fun fetch(model: ModelDescriptor, directory: File, progress: (Long, Boolean) -> Unit)

    /**
     * Runs [block], one install run of several models, as one session of this fetcher: the network fetcher reads the
     * model host's manifest at most once in it (R11). Nothing outlives the session, so the next run reads the
     * manifest again and sees a change. A fetcher with nothing to share runs [block] as it is.
     */
    fun <T> session(block: () -> T): T = block()

    companion object {
        /**
         * Downloads from the model host only (architecture 5.6, D53.1). First reads the host's manifest ([manifest])
         * and finds the model's pinned pair and files in it ([DistManifest.urls]); a manifest that does not list them
         * as pinned refuses the download with [Failure.VERIFY]. Then each file is fetched as its own seekable
         * Zstandard object with [ArtifactFetcher.fetchZstd] (Range resume, verified before and after decompression),
         * following only the redirects [redirects] allows (HTTPS to `ModelCatalog.DOWNLOAD_HOSTS`, at most five hops).
         * Every request, the manifest's included, sends [userAgent]: the app's own (D54), built where its version is
         * known. Inside a [session] the manifest is read once and kept for the session, in memory only; a read that
         * failed is not kept, so the next model of the run tries again.
         */
        fun network(
            userAgent: String,
            redirects: RedirectPolicy = RedirectPolicy.DOWNLOADS,
            manifest: () -> DistManifest = { DistManifest.fetch(userAgent, redirects = redirects) },
        ): ModelFetcher = NetworkFetcher(userAgent, redirects, read = manifest)
    }
}

/** [ModelFetcher.network]: the model host's manifest, then each pinned file as its own Zstandard download. */
private class NetworkFetcher(
    private val userAgent: String,
    private val redirects: RedirectPolicy,
    private val read: () -> DistManifest,
) : ModelFetcher {
    /** The manifest of the session that runs on this thread; a session is one install run on one IO thread. */
    private val session = ThreadLocal<Session?>()

    private class Session {
        var manifest: DistManifest? = null
    }

    override fun <T> session(block: () -> T): T {
        // A session inside a session shares the outer one.
        if (session.get() != null) return block()
        session.set(Session())
        try {
            return block()
        } finally {
            session.remove()
        }
    }

    private fun manifest(): DistManifest {
        val current = session.get() ?: return read()
        return current.manifest ?: read().also { current.manifest = it }
    }

    override fun fetch(model: ModelDescriptor, directory: File, progress: (Long, Boolean) -> Unit) {
        val urls = manifest().urls(model, redirects)
        var completed = 0L
        for (artifact in model.artifacts) {
            val download = checkNotNull(artifact.download) { "${artifact.name} has no pinned download" }
            val done = completed
            val url = urls.getValue(artifact.name)
            ArtifactFetcher.fetchZstd(url, download, artifact, directory, userAgent, redirects) { bytes, checking ->
                progress(done + bytes, checking)
            }
            completed += download.size
        }
    }
}

/**
 * The on-device models (docs/architecture.md, sections 4.6 and 5.6): which parts are installed under
 * [AppPaths.modelRoot], consent, the install plan and the install itself. [status] combines the readiness on disk
 * (read by [refresh]) with the progress of an install in flight, which [installBlocking] and the app's install owner
 * push. It is empty until the first [refresh]. There is no delete API: the design has no surface for it.
 *
 * Optional consent is stored per part; legacy `models.consentAt` authorizes both. Speech uses
 * `models.speechConsentAt` or installed Parakeet.
 * Only [recordConsent] records the permissions of the plan shown; [installBlocking] does not check consent.
 */
// Status, consent, plan and install of one area, as architecture 4.6 lists them.
@Suppress("TooManyFunctions")
class ModelRepository(
    private val paths: AppPaths,
    private val settings: SettingsRepository,
    private val dispatchers: CassiniDispatchers,
    private val fetcher: ModelFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Guards the fields below and the [state] updates. Never held across a suspension point or a download. */
    private val lock = ReentrantLock()

    /** Serializes snapshots so a slow refresh cannot overwrite a newer part consent. */
    private val refreshMutex = Mutex()
    private var ready: Map<ModelPart, Boolean> = emptyMap()
    private var consentRecorded: Set<ModelPart> = emptySet()
    private var speechConsentRecorded = false
    private val inFlight = HashMap<ModelPart, ModelStatus>()
    private val state = MutableStateFlow<Map<ModelPart, ModelStatus>>(emptyMap())

    val status: StateFlow<Map<ModelPart, ModelStatus>> = state.asStateFlow()

    /** Reads the readiness of every part and the consent again, and returns the new [status]. */
    suspend fun refresh(): Map<ModelPart, ModelStatus> = refreshMutex.withLock {
        val recorded = settings.consentedParts.first()
        val speechRecorded = ModelPart.SPEECH in recorded
        withContext(dispatchers.io) {
            val readiness = readiness()
            if (readiness[ModelPart.SPEECH] == true) removeRetiredParakeet(paths.modelRoot)
            lock.withLock {
                consentRecorded = recorded
                speechConsentRecorded = speechRecorded
                ready = readiness
                // A failure is shown until the part is installed or a new install starts.
                inFlight.keys.removeAll { readiness[it] == true }
                publish()
            }
        }
    }

    /** Explicit optional-model consent. Installed Parakeet authorizes only speech, never optional downloads. */
    suspend fun consented(): Boolean = settings.consentAt.first() != null

    suspend fun consented(part: ModelPart): Boolean =
        if (part == ModelPart.SPEECH) speechConsented() else part in settings.consentedParts.first()

    /** Last refreshed optional-download permission, for the synchronous job plan on its IO thread. */
    fun optionalDownloadsConsented(part: ModelPart): Boolean = lock.withLock { part in consentRecorded }

    suspend fun recordConsent(parts: Set<ModelPart>) {
        settings.recordConsent(clock(), parts)
        refresh()
    }

    /** "Download and transcribe" or "Download" was pressed. */
    suspend fun speechConsented(): Boolean =
        settings.speechConsentAt.first() != null || withContext(dispatchers.io) { ModelCatalog.PARAKEET.ready(paths.modelRoot) }

    suspend fun recordConsent(includeSpeech: Boolean = true, includeOptional: Boolean = true) {
        settings.recordConsent(clock(), includeSpeech, includeOptional)
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
    fun installBlocking(part: ModelPart, progress: (ModelStatus) -> Unit = {}): InstallResult =
        fetcher.session { installPartBlocking(part, progress) }

    /** [installBlocking] inside one fetcher session: the manifest is read once for every model of [part]. */
    private fun installPartBlocking(part: ModelPart, progress: (ModelStatus) -> Unit): InstallResult {
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
     * files (resuming `.part` files), then writes the marker durably: written to a temporary file and fsynced, renamed,
     * and the directory fsynced, so the renames of the files and the marker survive a power cut. [progress] gets the
     * bytes of the model's download and whether they are being checked. Any failure is thrown: a [UserFacingException]
     * (an interrupted socket read becomes CANCELLED, ENOSPC becomes SPACE, other raw I/O becomes DOWNLOAD).
     * For the app's `ModelInstaller` store,
     * which decides itself what is best effort,
     * on an IO thread.
     */
    /** Runs [block], an install run of several models, as one fetcher session (one manifest read, R11). */
    fun <T> installSession(block: () -> T): T = fetcher.session(block)

    @WorkerThread
    fun installModelBlocking(model: ModelDescriptor, progress: (Long, Boolean) -> Unit) {
        Threads.assertOffMain("ModelRepository.installModelBlocking")
        val directory = model.directory(paths.modelRoot)
        try {
            Files.createDirectories(directory.toPath())
        } catch (error: IOException) {
            throw UserFacingException(ArtifactFetcher.localFailure(error), error.message ?: "model directory")
                .apply { initCause(error) }
        }
        val marker = File(directory, model.markerFile)
        marker.delete()
        val failure = try {
            fetcher.fetch(model, directory, progress)
            null
        } catch (error: SocketTimeoutException) {
            fetchFailure(error)
        } catch (error: InterruptedIOException) {
            fetchFailure(error, cancelled = Thread.currentThread().isInterrupted)
        } catch (error: IOException) {
            fetchFailure(error)
        }
        if (failure != null) throw failure
        writeMarker(marker, model.markerValue)
        if (model == ModelCatalog.PARAKEET) removeRetiredParakeet(paths.modelRoot)
    }

    private fun fetchFailure(error: IOException, cancelled: Boolean = false): UserFacingException {
        val failure = when {
            cancelled -> Failure.CANCELLED
            ArtifactFetcher.localFailure(error) == Failure.SPACE -> Failure.SPACE
            else -> Failure.DOWNLOAD
        }
        return UserFacingException(failure, error.message ?: "I/O").apply { initCause(error) }
    }

    private fun writeMarker(marker: File, value: String) {
        val written = File(marker.parentFile, "${marker.name}.tmp")
        try {
            written.writeText(value)
            NoteFiles.syncFile(written)
            NoteFiles.replace(written, marker)
            NoteFiles.syncDirectory(marker.parentFile!!)
        } catch (error: IOException) {
            written.delete()
            val failure = ArtifactFetcher.localFailure(error)
            throw UserFacingException(failure, error.message ?: "marker").apply { initCause(error) }
        }
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
        val next = ModelPart.entries.mapNotNull { part ->
            val consent = if (part == ModelPart.SPEECH) {
                speechConsentRecorded || ready[ModelPart.SPEECH] == true
            } else {
                part in consentRecorded
            }
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
