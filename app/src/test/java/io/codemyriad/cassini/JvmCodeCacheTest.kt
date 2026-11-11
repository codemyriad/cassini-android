package io.codemyriad.cassini

import org.junit.Assert.assertTrue
import org.junit.Test

class JvmCodeCacheTest {
    @Test fun theWorkerReservesEnoughCodeCacheForTheWholeRobolectricSuite() {
        // Inspect the running worker, rather than the build script: C1's implicit 48 MiB limit can exhaust
        // method-handle space mid-suite and surface later as an unrelated coroutine timeout or leaked error.
        // Android's compile-time API omits JDK management classes; the pinned HotSpot test JVM supplies them.
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val diagnosticType = Class.forName("com.sun.management.HotSpotDiagnosticMXBean")
        val diagnostic = factory.getMethod("getPlatformMXBean", Class::class.java).invoke(null, diagnosticType)
        val option = diagnosticType.getMethod("getVMOption", String::class.java)
            .invoke(diagnostic, "ReservedCodeCacheSize")
        val reserved = Class.forName("com.sun.management.VMOption").getMethod("getValue")
            .invoke(option).toString().toLong()
        assertTrue("App worker code cache reserves $reserved bytes; needs at least 240 MiB", reserved >= 240L * 1024 * 1024)
    }
}
