package org.cassini.android.processing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cassini.android.adapters.NetworkCheck
import org.cassini.android.adapters.RepositoryModelStore
import org.cassini.core.Failure
import org.cassini.core.models.ModelPart
import org.cassini.data.models.ModelRepository
import org.cassini.data.models.ModelStatus
import org.cassini.engine.job.DownloadError
import org.cassini.engine.job.DownloadProgress
import org.cassini.engine.job.InstallOutcome
import org.cassini.engine.job.ModelInstaller
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

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
    }

    /** Guards [current] and the waiter counts. Every holder is a coroutine (architecture section 7). */
    private val mutex = Mutex()

    @Volatile private var current: Shared? = null

    private val mutableState = MutableStateFlow<InstallState>(InstallState.Idle)
    private val mutableOutcome = MutableStateFlow<InstallOutcome?>(null)

    val state: StateFlow<InstallState> = mutableState.asStateFlow()

    /** How the last install that ran to its end ended; null before the first one. */
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
    fun startDownload(parts: Set<ModelPart> = ModelPart.entries.toSet()): Job = scope.launch {
        try {
            install(parts, {}, standalone = true)
        } catch (_: CancellationException) {
            // A cancelled download keeps its .part files; there is nothing more to do.
        }
    }

    /** Cancels the install that runs now, if any. Its `.part` files stay. */
    fun cancel() {
        current?.task?.cancel(CancellationException("Model install cancelled"))
    }

    private suspend fun install(
        parts: Set<ModelPart>,
        onProgress: (DownloadProgress) -> Unit,
        standalone: Boolean,
    ): InstallOutcome {
        require(parts.isNotEmpty()) { "No model part to install" }
        while (true) {
            val (shared, joined) = mutex.withLock { attach(parts, onProgress, standalone) }
            if (!joined) {
                // Another part set installs, or a cancelled install has not ended: wait for its end, then try again.
                shared.task.join()
                continue
            }
            try {
                return shared.task.await()
            } finally {
                withContext(NonCancellable) { mutex.withLock { detach(shared, onProgress, standalone) } }
            }
        }
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
        if (standalone) shared.standalone++
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
        shared.parts.forEach(models::clearProgress)
        try {
            val store = RepositoryModelStore(models, modelRoot, network, models.consented())
            val outcome = ModelInstaller(store, io).install(shared.parts) { progress ->
                shared.progress = progress
                models.publishProgress(progress.part, statusOf(progress))
                publishState(shared)
                shared.listeners.forEach { it(progress) }
            }
            showOutcome(shared.parts, outcome)
            mutableOutcome.value = outcome
            return outcome
        } catch (cancelled: CancellationException) {
            shared.parts.forEach(models::clearProgress)
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                models.refresh()
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

    /** Clears the progress of every part, then shows the failures of [outcome] until the next install. */
    private fun showOutcome(parts: Set<ModelPart>, outcome: InstallOutcome) {
        parts.forEach(models::clearProgress)
        val failures = when (outcome) {
            is InstallOutcome.Failed -> mapOf(outcome.part to outcome.error)
            is InstallOutcome.Installed -> outcome.failed
            is InstallOutcome.NeedsConsent -> emptyMap()
        }
        failures.forEach { (part, error) ->
            models.publishProgress(part, ModelStatus.Failed(failureOf(error), error.name))
        }
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
            DownloadError.NO_CONNECTION, DownloadError.INTERRUPTED, DownloadError.HOST_NOT_ALLOWED -> Failure.DOWNLOAD
        }
    }
}
