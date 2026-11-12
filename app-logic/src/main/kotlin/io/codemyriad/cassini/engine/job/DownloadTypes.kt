package io.codemyriad.cassini.engine.job

import io.codemyriad.cassini.core.models.ModelPart

/**
 * One part of a model install: [doneBytes] of [totalBytes] of [part]'s missing models. While [verifying], the count
 * is the share of the files already checked ("Checking files — 80%"); otherwise the bytes downloaded.
 */
data class DownloadProgress(val part: ModelPart, val doneBytes: Long, val totalBytes: Long, val verifying: Boolean) {
    /** Whole percent of [totalBytes], 0 to 100. */
    val percent: Int
        get() = if (totalBytes <= 0) 0 else (doneBytes.coerceIn(0, totalBytes) * PERCENT / totalBytes).toInt()

    private companion object {
        const val PERCENT = 100L
    }
}

/**
 * Why a model install failed, as the download banners tell it (features.md section 13.4). Persisted in `jobs.failure`
 * as `JobFailure.DOWNLOAD_PREFIX` plus the name (a core `Failure` has names such as INSTALL too): never rename.
 */
enum class DownloadError {
    /** No validated network, or the connection timed out ("No connection"). */
    NO_CONNECTION,

    /** The transfer failed with a network: an HTTP status, an early end ("Download stopped"). */
    INTERRUPTED,

    /** The free-space check failed ("Not enough storage"). */
    NOT_ENOUGH_STORAGE,

    /** Size or SHA-256 did not match; the damaged file was deleted ("File check failed"). */
    FILE_CHECK_FAILED,

    /** A verified file could not be put in place ("Couldn't finish installing"). */
    INSTALL,

    /**
     * The model host no longer offers the pinned model this build needs ("Model not available", R11): nothing to
     * retry, the app must be updated.
     */
    MODEL_UNAVAILABLE,

    /** A redirect pointed outside the download hosts; shown as "Download stopped". */
    HOST_NOT_ALLOWED,

    /** Android stopped the download service (a time-out); "Download stopped" with its own body. */
    STOPPED_BY_SYSTEM,
}
