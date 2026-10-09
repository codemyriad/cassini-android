package org.cassini.engine.job

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.models.ModelPart
import org.cassini.core.models.ModelPlan
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException

/**
 * The model files [ModelInstaller] installs into, implemented in `:app` over `ModelRepository` and the connectivity
 * service. Every function blocks and runs on IO, never on the main thread.
 */
interface ModelStore {
    /** Consent recorded, or Parakeet installed (architecture section 5.6). */
    fun consented(): Boolean

    /** Whether the active network has `NET_CAPABILITY_VALIDATED`. */
    fun hasValidatedNetwork(): Boolean

    /** Whether [model] is installed and verified (its marker holds the pinned value). */
    fun ready(model: ModelDescriptor): Boolean

    /** Free bytes where the models are installed (`AppPaths.modelRoot`). */
    fun usableBytes(): Long

    /**
     * Installs [model] through `ArtifactFetcher` with Range resume: deletes its marker, fetches and verifies each
     * file, then writes the marker. [progress] receives a count in bytes of [model]'s download (0 to
     * `model.downloadBytes`) and whether it is verifying: while downloading, the bytes fetched over all its files;
     * while verifying, the files already done plus the bytes of the current check. Throws what `ArtifactFetcher`
     * throws (UserFacingException with DOWNLOAD, VERIFY, SPACE, INSTALL or CANCELLED, or an IOException).
     */
    fun installBlocking(model: ModelDescriptor, progress: (Long, Boolean) -> Unit)
}

/** How a [ModelInstaller.install] call ended. */
sealed interface InstallOutcome {
    /**
     * Speech recognition is ready, if it was asked for. Best-effort parts that are still missing are in [skipped]
     * (no validated network, or VOICES without the diarizer: nothing was fetched) or in [failed] with the reason.
     * They never fail a job: it degrades to one voice or no names.
     */
    data class Installed(
        val skipped: Set<ModelPart> = emptySet(),
        val failed: Map<ModelPart, DownloadError> = emptyMap(),
    ) : InstallOutcome

    /** The required [part] could not be installed. [requiredFreeBytes] is the space figure for NOT_ENOUGH_STORAGE. */
    data class Failed(val part: ModelPart, val error: DownloadError, val requiredFreeBytes: Long) : InstallOutcome

    /** No consent: nothing was fetched and no network call made. [missingBytes] is what the download would fetch. */
    data class NeedsConsent(val missingBytes: Long) : InstallOutcome
}

/**
 * Installs model parts in [ModelPart] order (architecture section 5.6). `ModelInstallOwner` in `:app` is its only
 * caller; jobs and the standalone download share it.
 *
 * - SPEECH is required: Parakeet must install. Its speech detector (VAD) is best effort, also when Parakeet is
 *   already installed.
 * - SPEAKERS (the diarizer) is best effort.
 * - VOICES (the voiceprint model) is best effort, and only installed once the diarizer is: prints come from the
 *   diarizer's speakers.
 * - Without a validated network the best-effort parts are skipped at once (no 30 s timeout). SPEECH is still tried:
 *   the check can be stale, and its failure says "No connection".
 * - Consent is checked again on entry: without it nothing is fetched.
 * - Free space for everything to be fetched is checked once before the first byte, against
 *   `ModelPlan.requiredFreeBytes`. `ArtifactFetcher` checks each file again.
 *
 * The work runs in `runInterruptible` on [io]: cancelling the caller interrupts the fetch, which keeps its `.part`
 * files, and ends in a CancellationException.
 */
class ModelInstaller(private val store: ModelStore, private val io: CoroutineDispatcher = Dispatchers.IO) {
    /** Installs the missing models of [parts]. [onProgress] is called on the IO thread. */
    suspend fun install(parts: Set<ModelPart>, onProgress: (DownloadProgress) -> Unit): InstallOutcome =
        runInterruptible(io) { installBlocking(parts, onProgress) }

    private fun installBlocking(parts: Set<ModelPart>, onProgress: (DownloadProgress) -> Unit): InstallOutcome {
        val missing = ModelPlan.of(store::ready).missing.filter { it.part in parts }
        // A Parakeet installed earlier without its detector: the detector alone is fetched (best effort).
        val speechMissing = missing.any { it.part == ModelPart.SPEECH }
        val detectorOnly = ModelPart.SPEECH in parts && !speechMissing && !store.ready(ModelCatalog.VAD)
        return when {
            missing.isEmpty() && !detectorOnly -> InstallOutcome.Installed()
            // Parakeet installed means consent, so a refusal here always has something missing to report.
            !store.consented() -> InstallOutcome.NeedsConsent(ModelPlan(missing).totalBytes)
            else -> installMissing(missing, detectorOnly, onProgress)
        }
    }

    private fun installMissing(
        missing: List<ModelPlan.Item>,
        detectorOnly: Boolean,
        onProgress: (DownloadProgress) -> Unit,
    ): InstallOutcome {
        val online = store.hasValidatedNetwork()
        val attempted = missing.filter { it.part == ModelPart.SPEECH || online }
        val skipped = missing.map { it.part }.filter { part -> attempted.none { it.part == part } }.toMutableSet()
        val failed = LinkedHashMap<ModelPart, DownloadError>()
        val requiredFreeBytes = ModelPlan(attempted).requiredFreeBytes
        val space = store.usableBytes() >= requiredFreeBytes
        // SPEECH comes first; when it fails nothing else is tried. A failed best-effort part does not stop the next.
        var speechError: DownloadError? = null
        for (item in attempted) {
            if (speechError != null) break
            checkActive()
            val error = if (space) attempt(item, skipped, onProgress) else DownloadError.NOT_ENOUGH_STORAGE
            if (item.part == ModelPart.SPEECH) speechError = error else if (error != null) failed[item.part] = error
        }
        if (speechError == null && online && detectorOnly) {
            installPart(ModelPlan.Item(ModelPart.SPEECH, listOf(ModelCatalog.VAD)), onProgress)
        }
        return speechError?.let { InstallOutcome.Failed(ModelPart.SPEECH, it, requiredFreeBytes) }
            ?: InstallOutcome.Installed(skipped, failed)
    }

    /** Installs [item], unless it is VOICES and the diarizer is missing: then it joins [skipped]. */
    private fun attempt(
        item: ModelPlan.Item,
        skipped: MutableSet<ModelPart>,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadError? {
        val waits = item.part == ModelPart.VOICES && !store.ready(ModelCatalog.DIARIZATION)
        if (waits) skipped += item.part
        return if (waits) null else installPart(item, onProgress)
    }

    /**
     * Installs [item]'s models in order. Returns null when the part's primary model is ready afterwards, else why
     * not. A failed extra (the detector) is ignored. Cancellation is thrown, never returned.
     */
    @Suppress("TooGenericExceptionCaught") // the store throws whatever ArtifactFetcher and the file system throw
    private fun installPart(item: ModelPlan.Item, onProgress: (DownloadProgress) -> Unit): DownloadError? {
        val total = item.downloadBytes
        var completed = 0L
        for (model in item.models) {
            checkActive()
            val error = try {
                store.installBlocking(model) { done, verifying ->
                    val bytes = completed + done.coerceIn(0, model.downloadBytes)
                    onProgress(DownloadProgress(item.part, bytes, total, verifying))
                }
                null
            } catch (failure: Exception) {
                errorOf(failure)
            }
            if (error != null && model == item.part.primary) return error
            completed += model.downloadBytes
        }
        return null
    }

    /** Why [failure] stopped a download; throws CancellationException when it was a cancellation. */
    private fun errorOf(failure: Exception): DownloadError {
        val interrupted = failure is InterruptedIOException && failure !is SocketTimeoutException &&
            Thread.currentThread().isInterrupted
        val cancelled = failure is CancellationException || failure is InterruptedException || interrupted ||
            (failure is UserFacingException && failure.failure == Failure.CANCELLED)
        if (cancelled) throw cancelled(failure)
        return downloadError(failure, store::hasValidatedNetwork)
    }

    private fun checkActive() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Model install cancelled")
    }

    private fun cancelled(cause: Exception) =
        CancellationException("Model install cancelled").apply { initCause(cause) }

    companion object {
        /**
         * The banner a download failure shows (architecture section 5.6, features.md section 13.4). A DOWNLOAD failure
         * or a network exception reads NO_CONNECTION when [online] says the network is not validated, INTERRUPTED
         * when it is; a timeout or an unknown host is always NO_CONNECTION. A redirect outside the allow-list
         * (`RedirectPolicy` reports "… is not allowed") is HOST_NOT_ALLOWED. SPACE, VERIFY and INSTALL map to their
         * own errors; anything else did not finish installing.
         */
        fun downloadError(failure: Throwable, online: () -> Boolean): DownloadError = when (failure) {
            is UserFacingException -> when (failure.failure) {
                Failure.SPACE -> DownloadError.NOT_ENOUGH_STORAGE
                Failure.VERIFY -> DownloadError.FILE_CHECK_FAILED
                Failure.DOWNLOAD -> when {
                    failure.message.orEmpty().endsWith(REDIRECT_REFUSED) -> DownloadError.HOST_NOT_ALLOWED
                    online() -> DownloadError.INTERRUPTED
                    else -> DownloadError.NO_CONNECTION
                }
                else -> DownloadError.INSTALL
            }
            is SocketTimeoutException, is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                DownloadError.NO_CONNECTION
            is IOException -> if (online()) DownloadError.INTERRUPTED else DownloadError.NO_CONNECTION
            else -> DownloadError.INSTALL
        }

        /** The end of `RedirectPolicy`'s message for a redirect to a host or scheme it does not allow. */
        private const val REDIRECT_REFUSED = "is not allowed"
    }
}
