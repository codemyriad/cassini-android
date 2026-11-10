package io.codemyriad.cassini.processing

import io.codemyriad.cassini.adapters.NetworkCheck
import io.codemyriad.cassini.adapters.RepositoryModelStore
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.data.models.ModelRepository
import io.codemyriad.cassini.data.models.ModelStatus
import io.codemyriad.cassini.engine.job.DownloadError
import io.codemyriad.cassini.engine.job.DownloadProgress
import io.codemyriad.cassini.engine.job.InstallOutcome
import io.codemyriad.cassini.engine.job.ModelInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/** What [ModelInstallOwner] does now. */
sealed interface InstallState {
    data object Idle : InstallState

    /**
     * An install of [parts] runs. [progress] is its latest report (null before the first byte). [standalone] is true
     * while the download from Settings or a banner waits on it; [waiters] counts every caller that waits on it.
     */
    data class Installing(
        val parts: Set<ModelPart>,
        val progress: DownloadProgress?,
        val standalone: Boolean,
        val waiters: Int,
    ) : InstallState
}

/**
 * The only caller of [ModelInstaller] (architecture 5.1 and 5.6). Jobs and the standalone download share it:
 *
 * - At most one install runs at a time.
 * - A request for the same part set while one runs joins it: it waits on the same [Deferred] and gets its progress and
 *   outcome. A request for another part set waits for the running install to end, then starts its own, which finds
 *   the files of the first one installed. So every artifact is fetched at most once.
 * - Progress goes into `ModelRepository.status` (Downloading or Verifying per part), into [state], and to each caller.
 *   A failure stays in the status until the next install of that part.
 * - A caller that is cancelled stops waiting. When no caller waits any more, the install is cancelled. [cancel]
 *   cancels the shared install at once; every waiter then gets a CancellationException. A cancelled install
 *   interrupts the fetch, and `ArtifactFetcher` keeps its `.part` files for a Range resume.
 *
 * The standalone download is no `jobs` row: its only state is [state] and the `.part` files (architecture 3.3).
 */
@Suppress("TooManyFunctions") // the install API of architecture 5.6, plus the stop by Android of 5.7
class ModelInstallOwner(
    private val models: ModelRepository,
    private val modelRoot: File,
    private val network: NetworkCheck,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private class Shared(val parts: Set<ModelPart>) {
        lateinit var task: Deferred<InstallOutcome>
        val listeners = CopyOnWriteArrayList<(DownloadProgress) -> Unit>()

        // Guarded by the owner's mutex.
        var waiters = 0
        var standalone = 0

        @Volatile var progress: DownloadProgress? = null

        /** Set by [stopDownloads]: when this install ends cancelled, it shows as "Download stopped". */
        @Volatile var stopped = false
    }

    /** Guards [current] and the waiter counts. Every holder is a coroutine (architecture section 7). */
    private val mutex = Mutex()

    @Volatile private var current: Shared? = null

    @Volatile private var lastStandaloneParts: Set<ModelPart>? = null

    /** The standalone downloads ([startDownload]) that have not ended. */
    private val downloads = CopyOnWriteArraySet<Job>()
    private val downloadLock = Any()
    private var downloadGeneration = 0L
    private val requests = mutableMapOf<Set<ModelPart>, Job>()
    private val requestedCounts = mutableMapOf<ModelPart, Int>()
    private val standaloneFailures = mutableMapOf<ModelPart, DownloadError>()

    private val mutableState = MutableStateFlow<InstallState>(InstallState.Idle)
    private val mutableOutcome = MutableStateFlow<InstallOutcome?>(null)

    val state: StateFlow<InstallState> = mutableState.asStateFlow()

    /**
     * The last standalone outcome, including unrelated failures that are still retryable. A user cancel does not
     * add a failure. A stop by Android ([stopDownloads]) reports STOPPED_BY_SYSTEM.
     */
    val lastOutcome: StateFlow<InstallOutcome?> = mutableOutcome.asStateFlow()

    /**
     * Installs the missing models of [parts], or joins the install of the same parts that runs now. [onProgress] is
     * called on an IO thread. Cancelling the caller stops its wait; the install goes on while another caller waits.
     */
    suspend fun install(parts: Set<ModelPart>, onProgress: (DownloadProgress) -> Unit = {}): InstallOutcome =
        install(parts, onProgress, standalone = false)

    /**
     * The download from Settings or a banner, with no note (architecture 5.6): installs [parts] in the background
     * and shows its progress through [state] and `ModelRepository.status`. [cancel] stops it.
     */
    fun startDownload(parts: Set<ModelPart>? = null): Job {
        if (parts != null) return requestDownload(parts.toSet())
        return synchronized(downloadLock) {
            val generation = downloadGeneration
            val retry = scope.launch(start = CoroutineStart.LAZY) {
                val requested = lastStandaloneParts?.takeIf { it.isNotEmpty() } ?: allowedRetryParts()
                val download = synchronized(downloadLock) {
                    checkGeneration(generation)
                    requestDownload(requested)
                }
                try {
                    download.join()
                } finally {
                    if (!isActive) withContext(NonCancellable) { download.cancelAndJoin() }
                }
            }
            track(retry)
            retry.start()
            retry
        }
    }

    private fun requestDownload(parts: Set<ModelPart>): Job = synchronized(downloadLock) {
        require(parts.isNotEmpty()) { "No model part to download" }
        requests[parts]?.takeUnless { it.isCompleted || it.isCancelled }?.let { return@synchronized it }
        val generation = downloadGeneration
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                install(parts, {}, standalone = true, generation = generation)
            } catch (_: CancellationException) {
                // A user cancel leaves resumable files, without reporting a download failure.
            }
        }
        requests[parts] = job
        lastStandaloneParts = (lastStandaloneParts.orEmpty() + parts).filterNot(::ready).toSet()
        if (standaloneFailures.keys.removeAll(parts) || standaloneFailures.isEmpty()) publishFailures()
        parts.forEach { part ->
            val count = requestedCounts[part] ?: 0
            requestedCounts[part] = count + 1
            markRequested(part)
        }
        track(job)
        job.invokeOnCompletion { completeRequest(parts, job) }
        job.start()
        job
    }

    private fun markRequested(part: ModelPart) {
        when (models.status.value[part]) {
            ModelStatus.Ready, is ModelStatus.Downloading, is ModelStatus.Verifying -> Unit
            else -> models.publishProgress(part, ModelStatus.Downloading(0, 0, part.models.sumOf { it.downloadBytes }))
        }
    }

    private fun track(job: Job) {
        downloads += job
        job.invokeOnCompletion { downloads -= job }
    }

    private fun completeRequest(parts: Set<ModelPart>, job: Job) = synchronized(downloadLock) {
        if (requests[parts] === job) requests.remove(parts)
        parts.forEach { part ->
            val remaining = (requestedCounts[part] ?: 1) - 1
            if (remaining == 0) {
                requestedCounts.remove(part)
                val running = current?.let { it.task.isActive && part in it.parts } == true
                val status = models.status.value[part]
                if (!running && (status is ModelStatus.Downloading || status is ModelStatus.Verifying)) {
                    models.clearProgress(part)
                }
            } else {
                requestedCounts[part] = remaining
            }
        }
        pruneCompletedParts()
    }

    private fun ready(part: ModelPart): Boolean = models.status.value[part] == ModelStatus.Ready

    private fun pruneCompletedParts() = synchronized(downloadLock) {
        lastStandaloneParts = lastStandaloneParts?.filterNot(::ready)?.toSet()
        if (standaloneFailures.keys.removeAll { ready(it) }) publishFailures()
    }

    private fun publishFailures() {
        mutableOutcome.value = standaloneFailures.takeIf { it.isNotEmpty() }
            ?.let { InstallOutcome.Installed(failed = it.toMap()) }
    }

    /** A retry never adds a model role that the original consent did not authorize. */
    private suspend fun allowedRetryParts(): Set<ModelPart> {
        val allowed = ModelPart.entries.filter { models.consented(it) }.toSet()
        // With no permission, preserve NeedsConsent rather than reporting an empty successful install.
        return allowed.ifEmpty { ModelPart.entries.toSet() }
    }

    /**
     * Android stopped the foreground service during a standalone download (`onTimeout`, architecture 5.7): every
     * standalone wait ends, and the install ends when no job waits on it either. The `.part` files stay for a Range
     * resume. When the install ends cancelled, each of its parts that is not installed shows "Download stopped"
     * (INTERRUPTED) until the next install, and [lastOutcome] tells it, so that the user can start it again. False
     * when no standalone download ran.
     */
    suspend fun stopDownloads(): Boolean {
        val waiting = synchronized(downloadLock) {
            downloads.toList().also { if (it.isNotEmpty()) downloadGeneration++ }
        }
        if (waiting.isEmpty()) return false
        mutex.withLock { current?.takeIf { it.standalone > 0 }?.stopped = true }
        waiting.forEach { it.cancel(CancellationException("Android stopped the download")) }
        waiting.joinAll()
        return true
    }

    /** Cancels the active install and queued requests. Their `.part` files stay. */
    fun cancel() {
        synchronized(downloadLock) {
            downloadGeneration++
            downloads.toList().forEach { it.cancel(CancellationException("Model download cancelled")) }
            current?.task?.cancel(CancellationException("Model install cancelled"))
        }
    }

    private suspend fun install(
        parts: Set<ModelPart>,
        onProgress: (DownloadProgress) -> Unit,
        standalone: Boolean,
        generation: Long? = null,
    ): InstallOutcome {
        require(parts.isNotEmpty()) { "No model part to install" }
        while (true) {
            val (shared, joined) = mutex.withLock {
                synchronized(downloadLock) {
                    if (generation != null) checkGeneration(generation)
                    attach(parts, onProgress, standalone)
                }
            }
            if (!joined) {
                // Another part set installs, or a cancelled install has not ended: wait for its end, then try again.
                shared.task.join()
                continue
            }
            try {
                return shared.task.await()
            } finally {
                withContext(NonCancellable) {
                    val last = mutex.withLock {
                        detach(shared, onProgress, standalone)
                        shared.waiters == 0
                    }
                    // A user-cancelled standalone job is done after its cleanup; Android timeout still returns promptly.
                    if (standalone && last && !shared.stopped) shared.task.join()
                }
            }
        }
    }

    /** Fence queued standalone requests before cancelling the active task. */
    private fun checkGeneration(generation: Long) {
        if (generation != downloadGeneration) throw CancellationException("Model download cancelled")
    }

    /**
     * Joins or starts the install of [parts]. False with the current install when another part set runs, or when the
     * current install is being cancelled: its fetch can block until the read timeout, and a new install must not fetch
     * the same files at the same time. [current] is cleared only when that task has ended. A task that was cancelled
     * before its body ran never clears [current] itself: it is cleared here and on its completion.
     */
    private fun attach(
        parts: Set<ModelPart>,
        listener: (DownloadProgress) -> Unit,
        standalone: Boolean,
    ): Pair<Shared, Boolean> {
        current?.takeIf { it.task.isCompleted }?.let(::release)
        val running = current
        if (running != null && (!running.task.isActive || running.parts != parts)) return running to false
        val shared = running ?: Shared(parts)
        shared.waiters++
        if (standalone) {
            shared.standalone++
        }
        shared.listeners += listener
        if (running == null) {
            shared.task = scope.async(io, CoroutineStart.LAZY) { run(shared) }
            current = shared
            // A LAZY task cancelled before its dispatch never enters run(): its finally does not clear the owner.
            shared.task.invokeOnCompletion { scope.launch(NonCancellable) { mutex.withLock { release(shared) } } }
            shared.task.start()
        }
        publishState(shared)
        return shared to true
    }

    private fun detach(shared: Shared, listener: (DownloadProgress) -> Unit, standalone: Boolean) {
        shared.listeners -= listener
        shared.waiters--
        if (standalone) shared.standalone--
        if (shared.waiters == 0 && shared.task.isActive) {
            shared.task.cancel(CancellationException("Nobody waits for the model install"))
        }
        publishState(shared)
    }

    private suspend fun run(shared: Shared): InstallOutcome {
        // A new install drops the failure an earlier one left in the status.
        synchronized(downloadLock) {
            shared.parts.filterNot { it in requestedCounts }.forEach(models::clearProgress)
        }
        try {
            val consented = shared.parts.all { models.consented(it) }
            val store = RepositoryModelStore(models, modelRoot, network, consented)
            val outcome = ModelInstaller(store, io).install(shared.parts) { progress ->
                shared.progress = progress
                models.publishProgress(progress.part, statusOf(progress))
                publishState(shared)
                shared.listeners.forEach { it(progress) }
            }
            val standalone = mutex.withLock { shared.standalone > 0 }
            showOutcome(shared.parts, outcome, standalone)
            if (standalone) rememberOutcome(shared.parts, outcome)
            return outcome
        } catch (cancelled: CancellationException) {
            if (shared.stopped) {
                // Android stopped it (stopDownloads): "Download stopped" until the next install. The refresh below
                // shows the parts that were installed as Ready again.
                val error = DownloadError.STOPPED_BY_SYSTEM
                shared.parts.forEach { models.publishProgress(it, ModelStatus.Failed(failureOf(error), error.name)) }
                val part = shared.progress?.part ?: shared.parts.min()
                rememberOutcome(shared.parts, InstallOutcome.Failed(part, error, requiredFreeBytes = 0))
            } else {
                shared.parts.forEach(models::clearProgress)
            }
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                models.refresh()
                pruneCompletedParts()
                mutex.withLock { release(shared) }
            }
        }
    }

    /** Clears [current] when it is [shared] (under the mutex): the owner is Idle until the next install. */
    private fun release(shared: Shared) {
        if (current !== shared) return
        current = null
        mutableState.value = InstallState.Idle
    }

    private fun publishState(shared: Shared) {
        if (current !== shared || !shared.task.isActive) return
        mutableState.value =
            InstallState.Installing(shared.parts, shared.progress, shared.standalone > 0, shared.waiters)
    }

    /** Only explicitly retried or successfully installed roles can remove an earlier standalone failure. */
    private fun rememberOutcome(parts: Set<ModelPart>, outcome: InstallOutcome) = synchronized(downloadLock) {
        val previous = standaloneFailures.toMap()
        val unrelated = previous.keys.any { it !in parts }
        standaloneFailures.keys.removeAll(parts)
        standaloneFailures.putAll(failures(outcome, standalone = true))
        if (!unrelated) {
            mutableOutcome.value = outcome
        } else if (standaloneFailures != previous) {
            publishFailures()
        }
    }

    /** Clears the progress of every part, then shows the failures of [outcome] until the next install. */
    private fun showOutcome(parts: Set<ModelPart>, outcome: InstallOutcome, standalone: Boolean) {
        parts.forEach(models::clearProgress)
        failures(outcome, standalone).forEach { (part, error) ->
            models.publishProgress(part, ModelStatus.Failed(failureOf(error), error.name))
        }
    }

    private fun failures(outcome: InstallOutcome, standalone: Boolean): Map<ModelPart, DownloadError> = when (outcome) {
        is InstallOutcome.Failed -> mapOf(outcome.part to outcome.error)
        is InstallOutcome.Installed -> {
            val error = if (network.validated()) DownloadError.INSTALL else DownloadError.NO_CONNECTION
            val skipped = if (standalone) outcome.skipped.associateWith { error } else emptyMap()
            skipped + outcome.failed
        }
        is InstallOutcome.NeedsConsent -> emptyMap()
    }

    internal companion object {
        private const val PERCENT = 100L

        fun statusOf(progress: DownloadProgress): ModelStatus = if (progress.verifying) {
            ModelStatus.Verifying(progress.percent)
        } else {
            val total = progress.totalBytes.coerceAtLeast(0)
            val bytes = progress.doneBytes.coerceIn(0, total)
            ModelStatus.Downloading((if (total == 0L) 0 else bytes * PERCENT / total).toInt(), bytes, total)
        }

        /** The core failure a download error stands for in `ModelStatus.Failed`; the error name is its detail. */
        fun failureOf(error: DownloadError): Failure = when (error) {
            DownloadError.NOT_ENOUGH_STORAGE -> Failure.SPACE
            DownloadError.FILE_CHECK_FAILED -> Failure.VERIFY
            DownloadError.INSTALL -> Failure.INSTALL
            DownloadError.MODEL_UNAVAILABLE -> Failure.MODEL_UNAVAILABLE
            DownloadError.NO_CONNECTION,
            DownloadError.INTERRUPTED,
            DownloadError.HOST_NOT_ALLOWED,
            DownloadError.STOPPED_BY_SYSTEM,
            -> Failure.DOWNLOAD
        }
    }
}
