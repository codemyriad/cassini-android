package io.codemyriad.cassini.core.models

/** Process capability, not the device's list of ABIs. Unknown probes must fail closed. */
enum class WhistleCpu {
    ARM64_DOTPROD,
    ARM64_BASELINE,
    OTHER_PROCESS,
    UNKNOWN,
}

/**
 * Eligibility for the pinned Needle 3.3.0 dispatch, before any engine is loaded. ELIGIBLE does not mean that
 * verified Included assets or the desktop-equivalent pipeline are ready. It never selects another recognizer.
 */
enum class WhistleCapability {
    ELIGIBLE,
    ANDROID_TOO_OLD,
    UNSUPPORTED_PROCESS,
    DOT_PRODUCT_UNAVAILABLE,
    CPU_PROBE_FAILED,
    ;

    companion object {
        // The API 26 system math/feature mask differs from the reference. API 27/28 are unvalidated.
        const val MIN_API = 29

        fun assess(apiLevel: Int, cpu: WhistleCpu): WhistleCapability {
            if (apiLevel < MIN_API) return ANDROID_TOO_OLD
            return when (cpu) {
                WhistleCpu.ARM64_DOTPROD -> ELIGIBLE
                WhistleCpu.OTHER_PROCESS -> UNSUPPORTED_PROCESS
                WhistleCpu.UNKNOWN -> CPU_PROBE_FAILED
                WhistleCpu.ARM64_BASELINE -> DOT_PRODUCT_UNAVAILABLE
            }
        }
    }
}
