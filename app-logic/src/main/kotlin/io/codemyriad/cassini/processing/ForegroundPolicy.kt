package io.codemyriad.cassini.processing

/**
 * What the foreground service must be while the coordinator works (architecture 5.7). [NONE]: nothing to do, the
 * service may stop. [DATA_SYNC]: downloads, the start of a job, and inference on API 29-34. [MEDIA_PROCESSING]:
 * inference on API 35+. Below API 29 the service type is ignored (`ServiceCompat` starts it untyped).
 */
enum class ForegroundNeed { NONE, DATA_SYNC, MEDIA_PROCESSING }

/** Pure service-type policy; Android supplies the API and maps the current job phase. */
object ForegroundPolicy {
    const val MEDIA_PROCESSING_API = 35

    fun need(sdkInt: Int, processing: Boolean, working: Boolean): ForegroundNeed = when {
        processing -> if (sdkInt >= MEDIA_PROCESSING_API) ForegroundNeed.MEDIA_PROCESSING else ForegroundNeed.DATA_SYNC
        working -> ForegroundNeed.DATA_SYNC
        else -> ForegroundNeed.NONE
    }
}
