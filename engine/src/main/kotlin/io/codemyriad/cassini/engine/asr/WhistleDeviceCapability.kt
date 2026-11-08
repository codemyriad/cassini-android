package io.codemyriad.cassini.engine.asr

import android.os.Build
import io.codemyriad.cassini.core.models.WhistleCapability
import io.codemyriad.cassini.core.models.WhistleCpu

/**
 * A system-only probe: no Needle constructors, model access, instruction override or inference. Future Included
 * readiness and worker creation must require ELIGIBLE as well as verified assets and the accepted pipeline.
 */
object WhistleDeviceCapability {
    fun current(): WhistleCapability = if (Build.VERSION.SDK_INT < WhistleCapability.MIN_API) {
        WhistleCapability.ANDROID_TOO_OLD
    } else {
        WhistleCapability.assess(Build.VERSION.SDK_INT, cpu())
    }

    /** Reports the executing process architecture and the kernel's real HWCAP_ASIMDDP bit. */
    fun cpu(): WhistleCpu = try {
        System.loadLibrary("cassini_capability")
        when (probe()) {
            PROBE_DOTPROD -> WhistleCpu.ARM64_DOTPROD
            PROBE_BASELINE -> WhistleCpu.ARM64_BASELINE
            PROBE_OTHER -> WhistleCpu.OTHER_PROCESS
            else -> WhistleCpu.UNKNOWN
        }
    } catch (_: LinkageError) {
        WhistleCpu.UNKNOWN
    } catch (_: SecurityException) {
        WhistleCpu.UNKNOWN
    }

    private external fun probe(): Int

    private const val PROBE_DOTPROD = 1
    private const val PROBE_BASELINE = 0
    private const val PROBE_OTHER = -1
}
